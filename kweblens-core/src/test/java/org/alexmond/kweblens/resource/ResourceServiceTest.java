package org.alexmond.kweblens.resource;

import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer;
import org.junit.jupiter.api.Test;

import org.alexmond.kweblens.cluster.ClusterRegistry;

import static org.assertj.core.api.Assertions.assertThat;

@EnableKubernetesMockClient(crud = true)
class ResourceServiceTest {

	static KubernetesClient client;

	static KubernetesMockServer server;

	private static final ResourceDescriptor CONFIG_MAPS = ResourceDescriptor.coreNamespaced("configmaps", "Config Maps",
			"ConfigMap", "configmaps");

	private ResourceService serviceFor(String clusterId) {
		ClusterRegistry registry = new ClusterRegistry();
		registry.register(clusterId, clusterId, client);
		return new ResourceService(registry);
	}

	@Test
	void listsNamespacesAsSummaries() {
		client.namespaces()
			.resource(new NamespaceBuilder().withNewMetadata().withName("kube-system").endMetadata().build())
			.create();

		ResourceService service = serviceFor("mock");

		assertThat(service.listNamespaces("mock")).anySatisfy((row) -> {
			assertThat(row.kind()).isEqualTo("Namespace");
			assertThat(row.name()).isEqualTo("kube-system");
		});
	}

	@Test
	void listsPodsInANamespace() {
		client.pods()
			.resource(new PodBuilder().withNewMetadata()
				.withName("nginx")
				.withNamespace("web")
				.endMetadata()
				.withNewStatus()
				.withPhase("Running")
				.endStatus()
				.build())
			.create();

		ResourceService service = serviceFor("mock");

		assertThat(service.listPods("mock", "web")).singleElement().satisfies((row) -> {
			assertThat(row.kind()).isEqualTo("Pod");
			assertThat(row.namespace()).isEqualTo("web");
			assertThat(row.name()).isEqualTo("nginx");
		});
	}

	@Test
	void getYamlReturnsTheResourceYaml() {
		client.configMaps()
			.resource(new ConfigMapBuilder().withNewMetadata()
				.withName("cm1")
				.withNamespace("default")
				.endMetadata()
				.addToData("key", "value")
				.build())
			.create();

		assertThat(serviceFor("mock").getYaml("mock", CONFIG_MAPS, "default", "cm1")).contains("cm1")
			.contains("ConfigMap");
	}

	@Test
	void getYamlIsNullForMissingResource() {
		assertThat(serviceFor("mock").getYaml("mock", CONFIG_MAPS, "default", "absent")).isNull();
	}

	@Test
	void applyServerSideAppliesTheManifest() {
		// The crud mock doesn't implement server-side apply; stub the apply PATCH.
		server.expect()
			.patch()
			.withPath("/api/v1/namespaces/default/configmaps/my-config?fieldManager=fabric8")
			.andReturn(200, "{\"apiVersion\":\"v1\",\"kind\":\"ConfigMap\","
					+ "\"metadata\":{\"name\":\"my-config\",\"namespace\":\"default\"},\"data\":{\"key\":\"value\"}}")
			.always();
		String yaml = """
				apiVersion: v1
				kind: ConfigMap
				metadata:
				  name: my-config
				  namespace: default
				data:
				  key: value
				""";

		ResourceSummary applied = serviceFor("mock").apply("mock", yaml);

		assertThat(applied.name()).isEqualTo("my-config");
		assertThat(applied.kind()).isEqualTo("ConfigMap");
	}

	@Test
	void watchDeliversAddedEvents() throws InterruptedException {
		ResourceService service = serviceFor("mock");
		// Latch specifically on our object (the shared crud server may replay
		// pre-existing ones).
		java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
		io.fabric8.kubernetes.client.Watch watch = service.watch("mock", CONFIG_MAPS, "default", (type, row) -> {
			if ("ADDED".equals(type) && "watched".equals(row.name())) {
				latch.countDown();
			}
		});

		client.configMaps()
			.resource(new ConfigMapBuilder().withNewMetadata()
				.withName("watched")
				.withNamespace("default")
				.endMetadata()
				.build())
			.create();

		assertThat(latch.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
		watch.close();
	}

	@Test
	void deleteRemovesTheResource() {
		client.configMaps()
			.resource(new ConfigMapBuilder().withNewMetadata()
				.withName("doomed")
				.withNamespace("default")
				.endMetadata()
				.build())
			.create();
		ResourceService service = serviceFor("mock");
		assertThat(service.getYaml("mock", CONFIG_MAPS, "default", "doomed")).isNotNull();

		service.delete("mock", CONFIG_MAPS, "default", "doomed");

		assertThat(service.getYaml("mock", CONFIG_MAPS, "default", "doomed")).isNull();
	}

	@Test
	void detailProjectsKindNameAndLabels() {
		client.configMaps()
			.resource(new ConfigMapBuilder().withNewMetadata()
				.withName("cm-detail")
				.withNamespace("default")
				.addToLabels("app", "x")
				.endMetadata()
				.build())
			.create();

		assertThat(serviceFor("mock").detail("mock", CONFIG_MAPS, "default", "cm-detail")).get().satisfies((d) -> {
			assertThat(d.kind()).isEqualTo("ConfigMap");
			assertThat(d.name()).isEqualTo("cm-detail");
			assertThat(d.labels()).containsEntry("app", "x");
		});
		assertThat(serviceFor("mock").detail("mock", CONFIG_MAPS, "default", "absent")).isEmpty();
	}

}
