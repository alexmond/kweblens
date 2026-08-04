package org.alexmond.kweblens.web.sim;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.api.model.Namespace;
import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.api.model.Node;
import io.fabric8.kubernetes.api.model.NodeBuilder;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.Quantity;
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

	/**
	 * How many Nodes the simulated cluster has. A small fixed number rather than
	 * {@code size}: nodes are the one kind a real cluster has few of however many
	 * workloads it runs, and three is enough for pods to spread over more than one name.
	 */
	private static final int NODES = 3;

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
		for (int n = 0; n < NODES; n++) {
			client.nodes().resource(node(n)).create();
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

	/**
	 * A node's name, in the shape a real cluster's is: a host FQDN, not a bare word. That
	 * matters to the UI and not just to realism — a name like {@code node-1} fits any
	 * column, so a simulator that used one could not render the case where a relation
	 * table's Node column is too narrow for its own values (#278), which is exactly the
	 * defect that shipped.
	 */
	private static String nodeName(int n) {
		return "node-" + n + ".sim.example.test";
	}

	private static Namespace namespace(String name) {
		return new NamespaceBuilder().withNewMetadata().withName(name).endMetadata().build();
	}

	/**
	 * A schedulable, Ready node. The simulator had none at all, so every node-flavoured
	 * surface — the Nodes list, its usage bars, the Node column of a pod relation table —
	 * was unrenderable without a live cluster.
	 */
	private static Node node(int n) {
		return new NodeBuilder().withNewMetadata()
			.withName(nodeName(n))
			.addToLabels("kubernetes.io/hostname", nodeName(n))
			.addToLabels("kubernetes.io/arch", "amd64")
			.addToLabels("node.kubernetes.io/instance-type", "sim.medium")
			.endMetadata()
			.withNewStatus()
			.addToCapacity("cpu", new Quantity("4"))
			.addToCapacity("memory", new Quantity("8Gi"))
			.addToCapacity("pods", new Quantity("110"))
			.addToAllocatable("cpu", new Quantity("4"))
			.addToAllocatable("memory", new Quantity("8Gi"))
			.addNewCondition()
			.withType("Ready")
			.withStatus("True")
			.withReason("KubeletReady")
			.endCondition()
			.withNewNodeInfo()
			.withKubeletVersion("v1.31.0")
			.withOsImage("Simulated Linux")
			.withKernelVersion("6.9.0-sim")
			.withContainerRuntimeVersion("containerd://1.7.0")
			.withArchitecture("amd64")
			.withOperatingSystem("linux")
			.withBootID("sim-boot")
			.withMachineID("sim-machine")
			.withSystemUUID("sim-uuid")
			.withKubeProxyVersion("v1.31.0")
			.endNodeInfo()
			.endStatus()
			.build();
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

	/**
	 * A pod scheduled onto a node and mounting the ConfigMap and Secret of the same
	 * index.
	 *
	 * <p>
	 * Both of those are what make the drawer's <em>relation</em> sections resolvable
	 * without a live cluster: nothing records who consumes a ConfigMap or Secret except
	 * the pods that mount them, so a simulator whose pods mounted nothing rendered every
	 * "Mounted By" section as "None." — and a pod with no {@code nodeName} left that
	 * table's Node column empty, which is the column #278 was about.
	 */
	private static Pod pod(int i, String ns) {
		return new PodBuilder().withNewMetadata()
			.withName("sim-pod-" + i)
			.withNamespace(ns)
			.addToLabels("app", "sim")
			.addToAnnotations("meta.helm.sh/release-name", "sim-release")
			.addToAnnotations("kweblens.sim/note", "seeded by the built-in simulator")
			.endMetadata()
			.withNewSpec()
			.withNodeName(nodeName(i % NODES))
			.addNewVolume()
			.withName("config")
			.withNewConfigMap()
			.withName("sim-config-" + i)
			.endConfigMap()
			.endVolume()
			.addNewVolume()
			.withName("token")
			.withNewSecret()
			.withSecretName("sim-secret-" + i)
			.endSecret()
			.endVolume()
			.addNewContainer()
			.withName("app")
			.withImage(IMAGE)
			.addNewVolumeMount()
			.withName("config")
			.withMountPath("/etc/sim")
			.withReadOnly(true)
			.endVolumeMount()
			.addNewVolumeMount()
			.withName("token")
			.withMountPath("/var/run/sim")
			.withReadOnly(true)
			.endVolumeMount()
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
