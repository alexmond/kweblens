package org.alexmond.kweblens.web.sim;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.ContainerBuilder;
import io.fabric8.kubernetes.api.model.ManagedFieldsEntry;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.Volume;
import io.fabric8.kubernetes.api.model.VolumeBuilder;
import io.fabric8.kubernetes.api.model.VolumeMount;
import io.fabric8.kubernetes.api.model.VolumeMountBuilder;

/**
 * Pods, in the two ways the old seeder's were not: <b>the right size</b> and <b>not all
 * healthy</b>.
 *
 * <p>
 * Size: a real pod on the live cluster is 7.8 KB (p90 11.4 KB, largest 19.5 KB) and the
 * seeded one was 739 bytes, because it had one container, no probes, no resources, no
 * conditions, no container statuses, no projected service-account volume and no
 * managedFields. Each of those is restored here, which is most of the missing 7 KB.
 *
 * <p>
 * Health: a perfectly healthy cluster is not a cluster, it is a screensaver. Every state
 * the dashboard can render — CrashLoopBackOff, ImagePullBackOff, an unschedulable
 * Pending, an OOMKill, an eviction, a completed job pod — was unreachable in the
 * simulator, which is why the contrast checker has never once measured
 * {@code .ov-card.danger} or a status pill and reported them {@code not present} instead.
 * About one pod in six is now in one of those states, chosen deterministically by index
 * (see {@link SimRandom}), so the same pod is broken in the same way on every run and at
 * every {@code size}.
 */
final class SimPods {

	static final String RUNNING = "Running";

	static final String CRASH_LOOP = "CrashLoopBackOff";

	static final String IMAGE_PULL = "ImagePullBackOff";

	static final String PENDING = "Pending";

	static final String OOM_KILLED = "OOMKilled";

	static final String FAILED = "Failed";

	static final String SUCCEEDED = "Succeeded";

	private static final String IMAGE = "registry.example.test/sim/app:1.4.2";

	private SimPods() {
	}

	/**
	 * The state this pod is in — deterministic, and shared with {@link SimEvents} so the
	 * Warning events on the cluster overview refer to pods that really are broken.
	 * Roughly: 4% crash-looping, 3% failing to pull, 3% unschedulable, 2% OOM-killed, 1%
	 * evicted, 3% completed.
	 */
	static String state(int index) {
		int roll = SimPayloads.roll("pod-state", index);
		if (roll < 4) {
			return CRASH_LOOP;
		}
		if (roll < 7) {
			return IMAGE_PULL;
		}
		if (roll < 10) {
			return PENDING;
		}
		if (roll < 12) {
			return OOM_KILLED;
		}
		if (roll < 13) {
			return FAILED;
		}
		return (roll < 16) ? SUCCEEDED : RUNNING;
	}

	/** An unschedulable Pending pod has no node, exactly as on a cluster. */
	static boolean unscheduled(int index) {
		return PENDING.equals(state(index));
	}

	static String podIp(int index) {
		return "10.42." + (index % 250) + '.' + ((index * 7) % 250);
	}

	/**
	 * Node addresses come from TEST-NET-2 (198.51.100.0/24), which RFC 5737 reserves for
	 * documentation. A plausible-looking private range would be indistinguishable from a
	 * real one leaking into a public repository, and this file is generated fixture data
	 * that ends up in screenshots.
	 */
	static String hostIp(int index) {
		return "198.51.100." + (30 + (index % 4));
	}

	static Pod pod(int index, String namespace, String nodeName, int owners) {
		SimRandom random = new SimRandom("pod", index);
		String name = "sim-pod-" + index;
		Map<String, String> labels = SimMeta.appLabels(index, "web");
		Map<String, String> annotations = annotations(index, namespace, name, random);
		ObjectMeta meta = SimMeta.meta("Pod", index, name, namespace, labels, annotations);
		int owner = index % Math.max(1, owners);
		meta.setOwnerReferences(
				List.of(SimMeta.owner("apps/v1", "ReplicaSet", "sim-rs-" + owner, SimMeta.uid("ReplicaSet", owner))));
		List<Container> containers = containers(index, random);
		meta.setManagedFields(managed(index, owner, labels, annotations, containers));
		return build(index, nodeName, meta, containers);
	}

	private static Map<String, String> annotations(int index, String namespace, String name, SimRandom random) {
		Map<String, String> annotations = SimMeta.commonAnnotations(index, "Pod", namespace);
		annotations.put("cni.projectcalico.org/podIP", podIp(index) + "/32");
		annotations.put("kubectl.kubernetes.io/restartedAt", SimMeta.created("restart", index));
		if (random.chance(9)) {
			// The long tail of pod size: one applied manifest stored as an annotation. A
			// minority, because on the live cluster annotations are only 1% of a pod —
			// most pods are made by a controller, which does not write this one.
			annotations.put("kubectl.kubernetes.io/last-applied-configuration",
					SimMeta.lastApplied("v1", "Pod", name, namespace, random.between(900, 8_000)));
		}
		return annotations;
	}

	private static Pod build(int index, String nodeName, ObjectMeta meta, List<Container> containers) {
		String state = state(index);
		return new PodBuilder().withMetadata(meta)
			.withNewSpec()
			.withContainers(containers)
			.withVolumes(volumes(index))
			.withNodeName(PENDING.equals(state) ? null : nodeName)
			.withServiceAccountName("sim-" + (index % 5))
			.withServiceAccount("sim-" + (index % 5))
			.withRestartPolicy("Always")
			.withTerminationGracePeriodSeconds(30L)
			.withDnsPolicy("ClusterFirst")
			.withSchedulerName("default-scheduler")
			.withPriority(0)
			.withEnableServiceLinks(true)
			.withPreemptionPolicy("PreemptLowerPriority")
			.withTolerations(SimPodParts.tolerations())
			.endSpec()
			.withStatus(SimPodStatus.status(index, state, containers))
			.build();
	}

	/** One to three containers, because a real pod is rarely exactly one. */
	private static List<Container> containers(int index, SimRandom random) {
		List<Container> containers = new ArrayList<>();
		containers.add(container(index, "app", random));
		if (random.chance(28)) {
			containers.add(container(index, "sidecar", random));
		}
		if (random.chance(8)) {
			containers.add(container(index, "metrics", random));
		}
		return containers;
	}

	private static Container container(int index, String name, SimRandom random) {
		ContainerBuilder container = new ContainerBuilder().withName(name)
			.withImage(IMAGE)
			.withImagePullPolicy("IfNotPresent")
			.withTerminationMessagePath("/dev/termination-log")
			.withTerminationMessagePolicy("File")
			.withVolumeMounts(mounts())
			.withEnv(SimPodParts.env(index, random))
			.addNewPort()
			.withName("http")
			.withContainerPort(8080)
			.withProtocol("TCP")
			.endPort();
		container.withNewResources()
			.addToRequests("cpu", new Quantity(random.between(10, 500) + "m"))
			.addToRequests("memory", new Quantity(random.between(32, 512) + "Mi"))
			.addToLimits("cpu", new Quantity(random.between(500, 2_000) + "m"))
			.addToLimits("memory", new Quantity(random.between(512, 2_048) + "Mi"))
			.endResources();
		return SimPodParts.probes(container, random).build();
	}

	private static List<VolumeMount> mounts() {
		VolumeMount config = new VolumeMountBuilder().withName("config")
			.withMountPath("/etc/sim")
			.withReadOnly(true)
			.build();
		VolumeMount token = new VolumeMountBuilder().withName("token")
			.withMountPath("/var/run/sim")
			.withReadOnly(true)
			.build();
		VolumeMount api = new VolumeMountBuilder().withName("kube-api-access")
			.withMountPath("/var/run/secrets/kubernetes.io/serviceaccount")
			.withReadOnly(true)
			.build();
		return List.of(config, token, api, new VolumeMountBuilder().withName("tmp").withMountPath("/tmp").build());
	}

	/**
	 * The ConfigMap and Secret of the same index, plus the projected service-account
	 * volume every real pod carries. The first two are what make the drawer's "Mounted
	 * By" relation sections resolvable without a live cluster; the third is several
	 * hundred bytes of pod that the old seeder did not have.
	 */
	private static List<Volume> volumes(int index) {
		Volume config = new VolumeBuilder().withName("config")
			.withNewConfigMap()
			.withName("sim-config-" + index)
			.withDefaultMode(420)
			.endConfigMap()
			.build();
		Volume token = new VolumeBuilder().withName("token")
			.withNewSecret()
			.withSecretName("sim-secret-" + index)
			.withDefaultMode(420)
			.endSecret()
			.build();
		Volume tmp = new VolumeBuilder().withName("tmp").withNewEmptyDir().endEmptyDir().build();
		return List.of(config, token, tmp, SimPodParts.apiAccess());
	}

	/**
	 * Three managers, as on a real pod: whoever applied it, the controller that adopted
	 * it, and the kubelet that owns its status.
	 */
	private static List<ManagedFieldsEntry> managed(int index, int owner, Map<String, String> labels,
			Map<String, String> annotations, List<Container> containers) {
		List<String> applied = new ArrayList<>(SimFields.metadataPaths(labels, annotations));
		applied.add("spec|volumes|.");
		applied.add("spec|dnsPolicy");
		applied.add("spec|restartPolicy");
		applied.add("spec|schedulerName");
		applied.add("spec|securityContext|.");
		applied.add("spec|serviceAccountName");
		applied.add("spec|terminationGracePeriodSeconds");
		containers.forEach((c) -> applied.addAll(SimFields.containerPaths("spec", c.getName())));
		String time = SimMeta.created("mf", index);
		List<String> adopted = List.of("metadata|ownerReferences|.",
				"metadata|ownerReferences|k:{\"uid\":\"" + SimMeta.uid("ReplicaSet", owner) + "\"}",
				"metadata|labels|pod-template-hash");
		return List.of(SimFields.entry("kubectl-client-side-apply", "Update", time, applied),
				SimFields.entry("kube-controller-manager", "Update", time, adopted),
				SimFields.statusEntry("kubelet", time, SimPodStatus.statusPaths(index, containers)));
	}

}
