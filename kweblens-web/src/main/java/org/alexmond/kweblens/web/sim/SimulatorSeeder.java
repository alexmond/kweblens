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
import io.fabric8.kubernetes.api.model.networking.v1.Ingress;
import io.fabric8.kubernetes.api.model.networking.v1.IngressBuilder;
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
			client.network().v1().ingresses().resource(ingress(i, ns)).create();
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
			.addToAnnotations("meta.helm.sh/release-name", "sim-release")
			.addToAnnotations("meta.helm.sh/release-namespace", ns)
			.endMetadata()
			.addToData("key", "value-" + i)
			.build();
	}

	/**
	 * An Ingress with a TLS host, so the drawer's Rules section renders its TLS chips.
	 * Every real cluster's objects carry annotations and most have TLS ingresses; a
	 * simulator that omitted both left the two chip styles unrenderable, which is how
	 * they shipped unreadable in the dark theme (#260).
	 */
	private static Ingress ingress(int i, String ns) {
		return new IngressBuilder().withNewMetadata()
			.withName("sim-ingress-" + i)
			.withNamespace(ns)
			.addToLabels("app", "sim")
			.addToAnnotations("meta.helm.sh/release-name", "sim-release")
			.endMetadata()
			.withNewSpec()
			.addNewTl()
			.withHosts("sim-" + i + ".example.test")
			.withSecretName("sim-tls-" + i)
			.endTl()
			.addNewRule()
			.withHost("sim-" + i + ".example.test")
			.withNewHttp()
			.addNewPath()
			.withPath("/")
			.withPathType("Prefix")
			.withNewBackend()
			.withNewService()
			.withName("sim-svc-" + i)
			.withNewPort()
			.withNumber(80)
			.endPort()
			.endService()
			.endBackend()
			.endPath()
			.endHttp()
			.endRule()
			.endSpec()
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
			.addToAnnotations("meta.helm.sh/release-name", "sim-release")
			.addToAnnotations("kweblens.sim/note", "seeded by the built-in simulator")
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
