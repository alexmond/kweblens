package org.alexmond.kweblens.resource;

import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.api.model.NodeBuilder;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.batch.v1.CronJob;
import io.fabric8.kubernetes.api.model.batch.v1.CronJobBuilder;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer;
import org.junit.jupiter.api.Test;

import org.alexmond.kweblens.cluster.ClusterRegistry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@EnableKubernetesMockClient(crud = true)
class ResourceServiceTest {

	static KubernetesClient client;

	static KubernetesMockServer server;

	private static final ResourceDescriptor CONFIG_MAPS = ResourceDescriptor.coreNamespaced("configmaps", "Config Maps",
			"ConfigMap", "configmaps");

	private static final ResourceDescriptor CRON_JOBS = ResourceDescriptor.namespaced("cronjobs", "Cron Jobs",
			"CronJob", "batch", "v1", "cronjobs");

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
			.withPath("/api/v1/namespaces/default/configmaps/my-config?fieldManager=fabric8&force=true")
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
	void watchRawDeliversFullObjects() throws InterruptedException {
		ResourceService service = serviceFor("mock");
		java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
		io.fabric8.kubernetes.client.Watch watch = service.watchRaw("mock", CONFIG_MAPS, "default", (type, obj) -> {
			if ("ADDED".equals(type) && "raw-watched".equals(obj.getMetadata().getName())) {
				latch.countDown();
			}
		});

		client.configMaps()
			.resource(new ConfigMapBuilder().withNewMetadata()
				.withName("raw-watched")
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
	void cordonMarksTheNodeUnschedulable() {
		client.nodes().resource(new NodeBuilder().withNewMetadata().withName("node-x").endMetadata().build()).create();
		ResourceService service = serviceFor("mock");

		service.setUnschedulable("mock", "node-x", true);

		ResourceDescriptor nodes = ResourceDescriptor.coreCluster("nodes", "Nodes", "Node", "nodes");
		assertThat(service.getYaml("mock", nodes, null, "node-x")).contains("unschedulable: true");
	}

	private CronJob newCronJob(String name) {
		return new CronJobBuilder().withNewMetadata()
			.withName(name)
			.withNamespace("default")
			.endMetadata()
			.withNewSpec()
			.withSchedule("* * * * *")
			.withNewJobTemplate()
			.withNewSpec()
			.withNewTemplate()
			.withNewSpec()
			.addNewContainer()
			.withName("c")
			.withImage("busybox")
			.endContainer()
			.withRestartPolicy("Never")
			.endSpec()
			.endTemplate()
			.endSpec()
			.endJobTemplate()
			.endSpec()
			.build();
	}

	@Test
	void rollbackRejectsAnUnsupportedKind() {
		assertThatThrownBy(() -> serviceFor("mock").rollback("mock", CONFIG_MAPS, "default", "cm"))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void rollbackDispatchesADeploymentToFabric8() {
		client.apps()
			.deployments()
			.resource(new io.fabric8.kubernetes.api.model.apps.DeploymentBuilder().withNewMetadata()
				.withName("dep-x")
				.withNamespace("default")
				.endMetadata()
				.build())
			.create();
		ResourceDescriptor deployments = ResourceDescriptor.namespaced("deployments", "Deployments", "Deployment",
				"apps", "v1", "deployments");
		try {
			serviceFor("mock").rollback("mock", deployments, "default", "dep-x");
		}
		catch (IllegalArgumentException ex) {
			throw new AssertionError("Deployment should dispatch to fabric8, not the unsupported-kind guard", ex);
		}
		catch (RuntimeException expected) {
			// The crud mock has no rollout history, so fabric8's undo surfaces an
			// error — but the Deployment branch was exercised, which is the point.
		}
	}

	@Test
	void suspendPatchesTheSuspendField() {
		client.batch().v1().cronjobs().resource(newCronJob("cj-suspend")).create();
		ResourceService service = serviceFor("mock");

		service.setSuspended("mock", CRON_JOBS, "default", "cj-suspend", true);

		assertThat(service.getYaml("mock", CRON_JOBS, "default", "cj-suspend")).contains("suspend: true");
	}

	@Test
	void triggerCronJobCreatesAnOwnedJob() {
		client.batch().v1().cronjobs().resource(newCronJob("cj-trigger")).create();

		serviceFor("mock").triggerCronJob("mock", "default", "cj-trigger");

		var jobs = client.batch().v1().jobs().inNamespace("default").list().getItems();
		assertThat(jobs).hasSize(1);
		Job job = jobs.get(0);
		assertThat(job.getMetadata().getName()).startsWith("cj-trigger-manual-");
		assertThat(job.getMetadata().getOwnerReferences()).anySatisfy((o) -> {
			assertThat(o.getKind()).isEqualTo("CronJob");
			assertThat(o.getName()).isEqualTo("cj-trigger");
		});
	}

	@Test
	void triggerCronJobRejectsAMissingCronJob() {
		assertThatThrownBy(() -> serviceFor("mock").triggerCronJob("mock", "default", "absent"))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void drainCordonsTheNodeAndSkipsDaemonSetPods() {
		client.nodes()
			.resource(new NodeBuilder().withNewMetadata().withName("node-drain").endMetadata().build())
			.create();
		client.pods()
			.resource(new PodBuilder().withNewMetadata()
				.withName("ds-pod")
				.withNamespace("default")
				.addNewOwnerReference()
				.withKind("DaemonSet")
				.withName("ds")
				.endOwnerReference()
				.endMetadata()
				.withNewSpec()
				.withNodeName("node-drain")
				.endSpec()
				.build())
			.create();
		ResourceService service = serviceFor("mock");

		service.drainNode("mock", "node-drain");

		ResourceDescriptor nodes = ResourceDescriptor.coreCluster("nodes", "Nodes", "Node", "nodes");
		assertThat(service.getYaml("mock", nodes, null, "node-drain")).contains("unschedulable: true");
		// DaemonSet-owned pod is skipped, not evicted.
		assertThat(client.pods().inNamespace("default").withName("ds-pod").get()).isNotNull();
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
