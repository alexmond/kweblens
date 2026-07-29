package org.alexmond.kweblens.web.sim;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.api.model.Namespace;
import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.fabric8.kubernetes.api.model.apps.ReplicaSet;
import io.fabric8.kubernetes.api.model.apps.ReplicaSetBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;

/**
 * Generates a configurable set of Kubernetes objects into a (mock-backed) client, so the
 * simulator cluster has large, realistic lists to render and watch. Kept separate from
 * the Spring wiring so it is unit-testable against a crud mock client.
 */
public final class SimulatorSeeder {

	private static final String IMAGE = "nginx:latest";

	private SimulatorSeeder() {
	}

	/**
	 * Create {@code namespaces} namespaces, then {@code size} of each kind spread across
	 * them.
	 */
	public static void seed(KubernetesClient client, SimulatorProperties props) {
		int nsCount = Math.max(1, props.getNamespaces());
		for (int n = 0; n < nsCount; n++) {
			client.namespaces().resource(namespace(ns(n))).create();
		}
		for (int i = 0; i < props.getSize(); i++) {
			String ns = ns(i % nsCount);
			client.configMaps().resource(configMap(i, ns)).create();
			client.secrets().resource(secret(i, ns)).create();
			client.pods().resource(pod(i, ns)).create();
			client.apps().replicaSets().resource(replicaSet(i, ns)).create();
			client.apps().deployments().resource(deployment(i, ns)).create();
		}
	}

	private static String ns(int n) {
		return "sim-ns-" + n;
	}

	private static Namespace namespace(String name) {
		return new NamespaceBuilder().withNewMetadata().withName(name).endMetadata().build();
	}

	private static ConfigMap configMap(int i, String ns) {
		return new ConfigMapBuilder().withNewMetadata()
			.withName("sim-config-" + i)
			.withNamespace(ns)
			.addToLabels("app", "sim")
			.endMetadata()
			.addToData("key", "value-" + i)
			.build();
	}

	private static Secret secret(int i, String ns) {
		return new SecretBuilder().withNewMetadata()
			.withName("sim-secret-" + i)
			.withNamespace(ns)
			.addToLabels("app", "sim")
			.endMetadata()
			.withType("Opaque")
			.addToData("token", "dmFsdWU=") // base64("value")
			.build();
	}

	private static Pod pod(int i, String ns) {
		return new PodBuilder().withNewMetadata()
			.withName("sim-pod-" + i)
			.withNamespace(ns)
			.addToLabels("app", "sim")
			.endMetadata()
			.withNewSpec()
			.addNewContainer()
			.withName("app")
			.withImage(IMAGE)
			.endContainer()
			.endSpec()
			.withNewStatus()
			.withPhase("Running")
			.endStatus()
			.build();
	}

	private static ReplicaSet replicaSet(int i, String ns) {
		return new ReplicaSetBuilder().withNewMetadata()
			.withName("sim-rs-" + i)
			.withNamespace(ns)
			.addToLabels("app", "sim")
			.endMetadata()
			.withNewSpec()
			.withReplicas(1)
			.withNewTemplate()
			.withNewMetadata()
			.addToLabels("app", "sim")
			.endMetadata()
			.withNewSpec()
			.addNewContainer()
			.withName("app")
			.withImage(IMAGE)
			.endContainer()
			.endSpec()
			.endTemplate()
			.endSpec()
			.withNewStatus()
			.withReplicas(1)
			.withReadyReplicas(1)
			.endStatus()
			.build();
	}

	private static Deployment deployment(int i, String ns) {
		return new DeploymentBuilder().withNewMetadata()
			.withName("sim-deploy-" + i)
			.withNamespace(ns)
			.addToLabels("app", "sim")
			.endMetadata()
			.withNewSpec()
			.withReplicas(1)
			.withNewTemplate()
			.withNewMetadata()
			.addToLabels("app", "sim")
			.endMetadata()
			.withNewSpec()
			.addNewContainer()
			.withName("app")
			.withImage(IMAGE)
			.endContainer()
			.endSpec()
			.endTemplate()
			.endSpec()
			.withNewStatus()
			.withReplicas(1)
			.withReadyReplicas(1)
			.endStatus()
			.build();
	}

}
