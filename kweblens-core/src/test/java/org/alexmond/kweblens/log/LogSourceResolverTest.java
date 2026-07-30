package org.alexmond.kweblens.log;

import java.util.List;
import java.util.Map;

import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import org.junit.jupiter.api.Test;

import org.alexmond.kweblens.cluster.ClusterRegistry;
import org.alexmond.kweblens.resource.ResourceDescriptor;
import org.alexmond.kweblens.resource.ResourceService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Source expansion is the part of multi-source logging with real branching, so it is
 * pinned here: a pod fans out to its containers, a workload fans out through its selector
 * to its pods, and the failure cases say why rather than silently following nothing.
 */
@EnableKubernetesMockClient(crud = true)
class LogSourceResolverTest {

	// Deliberately NOT static. A static client shares one mock API server across the
	// whole
	// class, so pods seeded by an earlier test survive into this one; carrying the same
	// `app: web` label, they then legitimately match the selector and make the workload
	// assertion order-dependent. An instance field gets a fresh server per test method.
	KubernetesClient client;

	private static final ResourceDescriptor DEPLOYMENTS = ResourceDescriptor.namespaced("deployments", "Deployments",
			"Deployment", "apps", "v1", "deployments");

	private LogSourceResolver resolverFor(String clusterId) {
		ClusterRegistry registry = new ClusterRegistry();
		registry.register(clusterId, clusterId, client);
		return new LogSourceResolver(registry, new ResourceService(registry));
	}

	private void pod(String name, Map<String, String> labels, List<String> containers, List<String> initContainers) {
		var spec = new PodBuilder().withNewMetadata()
			.withName(name)
			.withNamespace("app")
			.withLabels(labels)
			.endMetadata()
			.withNewSpec();
		for (String container : containers) {
			spec = spec.addNewContainer().withName(container).withImage("busybox").endContainer();
		}
		for (String container : initContainers) {
			spec = spec.addNewInitContainer().withName(container).withImage("busybox").endInitContainer();
		}
		client.pods().inNamespace("app").resource(spec.endSpec().build()).create();
	}

	@Test
	void expandsAPodToItsContainers() {
		pod("web-0", Map.of("app", "web"), List.of("nginx", "sidecar"), List.of("migrate"));

		List<LogSource> sources = resolverFor("mock").forPod("mock", "app", "web-0", false);

		assertThat(sources).extracting(LogSource::container).containsExactly("nginx", "sidecar");
		assertThat(sources).allSatisfy((s) -> assertThat(s.namespace()).isEqualTo("app"));
	}

	@Test
	void includesInitContainersLastOnlyWhenAsked() {
		pod("web-1", Map.of("app", "web"), List.of("nginx"), List.of("migrate"));

		List<LogSource> sources = resolverFor("mock").forPod("mock", "app", "web-1", true);

		// Init containers come last: their logs are usually finished and would otherwise
		// dilute a live tail.
		assertThat(sources).extracting(LogSource::container).containsExactly("nginx", "migrate");
	}

	@Test
	void expandsAWorkloadThroughItsSelectorToEveryPodAndContainer() {
		pod("web-a", Map.of("app", "web"), List.of("nginx", "sidecar"), List.of());
		pod("web-b", Map.of("app", "web"), List.of("nginx", "sidecar"), List.of());
		// Must NOT be picked up — different labels, so outside the selector.
		pod("other", Map.of("app", "other"), List.of("nginx"), List.of());
		client.apps()
			.deployments()
			.inNamespace("app")
			.resource(new DeploymentBuilder().withNewMetadata()
				.withName("web")
				.withNamespace("app")
				.endMetadata()
				.withNewSpec()
				.withNewSelector()
				.withMatchLabels(Map.of("app", "web"))
				.endSelector()
				.endSpec()
				.build())
			.create();

		List<LogSource> sources = resolverFor("mock").forWorkload("mock", DEPLOYMENTS, "app", "web", false);

		// Pods are name-ordered so colour assignment is stable across reconnects.
		assertThat(sources).extracting(LogSource::id)
			.containsExactly("app/web-a/nginx", "app/web-a/sidecar", "app/web-b/nginx", "app/web-b/sidecar");
	}

	@Test
	void sourceIdIsNamespaceQualified() {
		assertThat(new LogSource("app", "web-0", "nginx").id()).isEqualTo("app/web-0/nginx");
	}

	@Test
	void failsLoudlyForAMissingPod() {
		assertThatThrownBy(() -> resolverFor("mock").forPod("mock", "app", "nope", false))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("app/nope");
	}

	@Test
	void failsLoudlyForAMissingWorkload() {
		assertThatThrownBy(() -> resolverFor("mock").forWorkload("mock", DEPLOYMENTS, "app", "ghost", false))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("ghost");
	}

	@Test
	void failsWithAnExplanationWhenTheWorkloadHasNoMatchLabels() {
		// A selector-less workload would otherwise resolve to zero sources, which reads
		// as
		// "this workload has no logs" rather than "kweblens cannot resolve its pods".
		client.apps()
			.deployments()
			.inNamespace("app")
			.resource(new DeploymentBuilder().withNewMetadata()
				.withName("selectorless")
				.withNamespace("app")
				.endMetadata()
				.withNewSpec()
				.endSpec()
				.build())
			.create();

		assertThatThrownBy(() -> resolverFor("mock").forWorkload("mock", DEPLOYMENTS, "app", "selectorless", false))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("matchLabels");
	}

}
