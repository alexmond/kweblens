package org.alexmond.kweblens.web.sim;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.fabric8.kubernetes.api.model.ContainerImage;
import io.fabric8.kubernetes.api.model.ContainerImageBuilder;
import io.fabric8.kubernetes.api.model.Node;
import io.fabric8.kubernetes.api.model.NodeBuilder;
import io.fabric8.kubernetes.api.model.NodeStatus;
import io.fabric8.kubernetes.api.model.NodeStatusBuilder;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.Quantity;

/**
 * Nodes — few in number, large in bytes.
 *
 * <p>
 * A real node is 10.3 KB, which surprises people: most of it is {@code status.images},
 * the list of every image cached on that kubelet, plus five conditions with messages and
 * the addresses/daemonEndpoints/nodeInfo blocks. The seeded node was 809 bytes with none
 * of that, so anything that reasons about node payload — the node list, the node drawer,
 * a "how much does /counts cost" question — was measuring an object a tenth of the real
 * size.
 *
 * <p>
 * One node in three is <b>not</b> Ready, because a node list where nothing is ever wrong
 * cannot show the state that matters most on it. Its name is an FQDN for the reason
 * recorded in #278: a name like {@code node-1} fits any column, so a rig that used one
 * could not reproduce the too-narrow Node column that shipped.
 */
final class SimNodes {

	private static final String KUBELET_VERSION = "v1.31.0";

	private SimNodes() {
	}

	static String nodeName(int index) {
		return "node-" + index + ".sim.example.test";
	}

	/** The last node is NotReady, so the Nodes list has both states in it. */
	static boolean ready(int index, int total) {
		return total < 3 || index != total - 1;
	}

	static Node node(int index, int total) {
		String name = nodeName(index);
		Map<String, String> labels = labels(index, name);
		Map<String, String> annotations = Map.of("node.alpha.kubernetes.io/ttl", "0",
				"volumes.kubernetes.io/controller-managed-attach-detach", "true",
				"flannel.alpha.coreos.com/backend-data",
				"{\"VNI\":1,\"VtepMAC\":\"" + new SimRandom("mac", index).hex(12) + "\"}",
				"flannel.alpha.coreos.com/public-ip", SimPods.hostIp(index));
		ObjectMeta meta = SimMeta.meta("Node", index, name, null, labels, annotations);
		// Three managers on a node, as on a cluster: whatever joined it, the controller
		// manager that maintains its CIDR, and the kubelet that owns its whole status.
		String time = SimMeta.created("mf", index);
		meta.setManagedFields(
				List.of(SimFields.entry("kubeadm", "Update", time, SimFields.metadataPaths(labels, annotations)),
						SimFields.entry("kube-controller-manager", "Update", time,
								List.of("metadata|annotations|node.alpha.kubernetes.io/ttl", "spec|podCIDR",
										"spec|podCIDRs|.", "spec|providerID")),
						SimFields.statusEntry("kubelet", time, statusPaths())));
		boolean ready = ready(index, total);
		return new NodeBuilder().withMetadata(meta)
			.withNewSpec()
			.withPodCIDR("10.42." + index + ".0/24")
			.withPodCIDRs("10.42." + index + ".0/24")
			.withProviderID("sim://node-" + index)
			.endSpec()
			.withStatus(status(index, ready))
			.build();
	}

	/**
	 * What the kubelet owns: every condition, every address, capacity and allocatable.
	 */
	private static List<String> statusPaths() {
		List<String> paths = new ArrayList<>(List.of("status|addresses|.",
				"status|addresses|k:{\"type\":\"Hostname\"}|.", "status|addresses|k:{\"type\":\"Hostname\"}|address",
				"status|addresses|k:{\"type\":\"Hostname\"}|type", "status|addresses|k:{\"type\":\"InternalIP\"}|.",
				"status|addresses|k:{\"type\":\"InternalIP\"}|address",
				"status|addresses|k:{\"type\":\"InternalIP\"}|type", "status|allocatable|.", "status|allocatable|cpu",
				"status|allocatable|ephemeral-storage", "status|allocatable|memory", "status|allocatable|pods",
				"status|capacity|.", "status|capacity|cpu", "status|capacity|ephemeral-storage",
				"status|capacity|memory", "status|capacity|pods", "status|daemonEndpoints|kubeletEndpoint|Port",
				"status|images", "status|nodeInfo|architecture", "status|nodeInfo|bootID",
				"status|nodeInfo|containerRuntimeVersion", "status|nodeInfo|kernelVersion",
				"status|nodeInfo|kubeProxyVersion", "status|nodeInfo|kubeletVersion", "status|nodeInfo|machineID",
				"status|nodeInfo|operatingSystem", "status|nodeInfo|osImage", "status|nodeInfo|systemUUID"));
		for (String type : List.of("DiskPressure", "MemoryPressure", "PIDPressure", "Ready", "NetworkUnavailable")) {
			String base = "status|conditions|k:{\"type\":\"" + type + "\"}";
			paths.add(base + "|.");
			paths.add(base + "|lastHeartbeatTime");
			paths.add(base + "|lastTransitionTime");
			paths.add(base + "|message");
			paths.add(base + "|reason");
			paths.add(base + "|status");
			paths.add(base + "|type");
		}
		return paths;
	}

	private static Map<String, String> labels(int index, String name) {
		Map<String, String> labels = new LinkedHashMap<>();
		labels.put("kubernetes.io/hostname", name);
		labels.put("kubernetes.io/arch", "amd64");
		labels.put("kubernetes.io/os", "linux");
		labels.put("beta.kubernetes.io/arch", "amd64");
		labels.put("beta.kubernetes.io/os", "linux");
		labels.put("node.kubernetes.io/instance-type", "sim.medium");
		labels.put("topology.kubernetes.io/region", "sim-region");
		labels.put("topology.kubernetes.io/zone", "sim-zone-" + index);
		if (index == 0) {
			labels.put("node-role.kubernetes.io/control-plane", "true");
		}
		return labels;
	}

	private static NodeStatus status(int index, boolean ready) {
		return new NodeStatusBuilder().addToCapacity("cpu", new Quantity("8"))
			.addToCapacity("memory", new Quantity("32Gi"))
			.addToCapacity("pods", new Quantity("110"))
			.addToCapacity("ephemeral-storage", new Quantity("103880640Ki"))
			.addToAllocatable("cpu", new Quantity("8"))
			.addToAllocatable("memory", new Quantity("31Gi"))
			.addToAllocatable("pods", new Quantity("110"))
			.addToAllocatable("ephemeral-storage", new Quantity("95738055171"))
			.withImages(images(index))
			.withConditions(SimNodeConditions.conditions(index, ready))
			.addNewAddress()
			.withType("InternalIP")
			.withAddress(SimPods.hostIp(index))
			.endAddress()
			.addNewAddress()
			.withType("Hostname")
			.withAddress(nodeName(index))
			.endAddress()
			.withNewDaemonEndpoints()
			.withNewKubeletEndpoint()
			.withPort(10_250)
			.endKubeletEndpoint()
			.endDaemonEndpoints()
			.withNewNodeInfo()
			.withKubeletVersion(KUBELET_VERSION)
			.withKubeProxyVersion(KUBELET_VERSION)
			.withOsImage("Simulated Linux 42 (Sim)")
			.withKernelVersion("6.9.0-sim")
			.withContainerRuntimeVersion("containerd://1.7.0")
			.withArchitecture("amd64")
			.withOperatingSystem("linux")
			.withBootID(SimMeta.uid("boot", index))
			.withMachineID(new SimRandom("machine", index).hex(32))
			.withSystemUUID(SimMeta.uid("system", index))
			.endNodeInfo()
			.build();
	}

	/**
	 * The image cache. Real nodes list dozens with every tag and digest they hold, and it
	 * is the single biggest part of a node object.
	 */
	private static List<ContainerImage> images(int index) {
		SimRandom random = new SimRandom("images", index);
		List<ContainerImage> images = new ArrayList<>();
		for (int i = 0; i < 26; i++) {
			String repo = "registry.example.test/sim/" + random.word() + '-' + i;
			images
				.add(new ContainerImageBuilder().withNames(repo + "@sha256:" + random.hex(64), repo + ":1." + i + ".0")
					.withSizeBytes((long) random.between(12_000_000, 890_000_000))
					.build());
		}
		return images;
	}

}
