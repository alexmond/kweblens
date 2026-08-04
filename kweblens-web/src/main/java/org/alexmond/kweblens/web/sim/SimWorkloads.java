package org.alexmond.kweblens.web.sim;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import io.fabric8.kubernetes.api.model.ManagedFieldsEntry;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.PodTemplateSpec;
import io.fabric8.kubernetes.api.model.PodTemplateSpecBuilder;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.fabric8.kubernetes.api.model.apps.ReplicaSet;
import io.fabric8.kubernetes.api.model.apps.ReplicaSetBuilder;

/**
 * Deployments and ReplicaSets — the two kinds that are <b>bigger than a pod</b> on a real
 * cluster once managedFields are counted (46-48% of them, against a pod's 37%), because a
 * controller, a scaler and whoever applied the manifest all own fields on the same
 * object.
 *
 * <p>
 * A deployment is also where {@code last-applied-configuration} really lives: the whole
 * manifest, stored as one annotation string, is why the live cluster's deployments
 * average 5.9 KB and reach 15 KB. The old seeder's were 429 bytes with a one-line pod
 * template.
 *
 * <p>
 * A tenth of them are not at their desired replica count, which is what
 * {@code WorkloadHealth.replicas} reports as "2/3 ready" — the only way the Workloads
 * overview's attention states can be seen without a broken cluster to hand.
 */
final class SimWorkloads {

	private static final String IMAGE = "registry.example.test/sim/app:1.4.2";

	private static final String APPS_V1 = "apps/v1";

	private SimWorkloads() {
	}

	/** Desired replicas for this index — deterministic, 1 to 5. */
	static int replicas(int index) {
		return new SimRandom("replicas", index).between(1, 5);
	}

	/**
	 * Ready replicas: fewer than desired for one workload in ten, so the overview has
	 * something to be unhappy about.
	 */
	static int ready(int index) {
		int desired = replicas(index);
		return (SimPayloads.roll("workload-health", index) < 10) ? Math.max(0, desired - 1) : desired;
	}

	static Deployment deployment(int index, String namespace) {
		String name = "sim-deploy-" + index;
		Map<String, String> labels = SimMeta.appLabels(index, "web");
		Map<String, String> annotations = SimMeta.commonAnnotations(index, "Deployment", namespace);
		annotations.put("deployment.kubernetes.io/revision", String.valueOf(1 + (index % 9)));
		// One deployment in eight was applied from a manifest with a large env or a long
		// list of args, and carries all of it here. That is where the live cluster's 15
		// KB
		// deployments come from — without the occasional big one, the kind has a p90 and
		// a
		// max that are the same number, which no real kind does.
		SimRandom applied = new SimRandom("deploy-applied", index);
		if (applied.chance(16)) {
			annotations.put("kubectl.kubernetes.io/last-applied-configuration",
					SimMeta.lastApplied(APPS_V1, "Deployment", name, namespace,
							applied.chance(33) ? applied.between(4_000, 10_000) : applied.between(400, 1_800)));
		}
		ObjectMeta meta = SimMeta.meta("Deployment", index, name, namespace, labels, annotations);
		meta.setManagedFields(managed(index, labels, annotations));
		int desired = replicas(index);
		int ready = ready(index);
		return new DeploymentBuilder().withMetadata(meta)
			.withNewSpec()
			.withReplicas(desired)
			.withNewSelector()
			.addToMatchLabels("app", "sim")
			.addToMatchLabels("app.kubernetes.io/component", "web")
			.endSelector()
			.withTemplate(template(index, labels))
			.withNewStrategy()
			.withType("RollingUpdate")
			.withNewRollingUpdate()
			.withNewMaxSurge("25%")
			.withNewMaxUnavailable("25%")
			.endRollingUpdate()
			.endStrategy()
			.withRevisionHistoryLimit(10)
			.withProgressDeadlineSeconds(600)
			.endSpec()
			.withNewStatus()
			.withObservedGeneration(1L)
			.withReplicas(desired)
			.withUpdatedReplicas(desired)
			.withAvailableReplicas(ready)
			.withReadyReplicas(ready)
			.withUnavailableReplicas(desired - ready)
			.withConditions(SimConditions.deployment(index, ready == desired))
			.endStatus()
			.build();
	}

	static ReplicaSet replicaSet(int index, String namespace) {
		String name = "sim-rs-" + index;
		Map<String, String> labels = SimMeta.appLabels(index, "web");
		Map<String, String> annotations = SimMeta.commonAnnotations(index, "ReplicaSet", namespace);
		annotations.put("deployment.kubernetes.io/desired-replicas", String.valueOf(replicas(index)));
		annotations.put("deployment.kubernetes.io/max-replicas", String.valueOf(replicas(index) + 1));
		annotations.put("deployment.kubernetes.io/revision", String.valueOf(1 + (index % 9)));
		SimRandom random = new SimRandom("rs-applied", index);
		if (random.chance(20)) {
			// A ReplicaSet adopted from a client-side apply inherits the annotation,
			// which
			// is where the live cluster's 14 KB replicasets come from. A quarter of them,
			// so the kind has the spread its real counterpart does.
			annotations.put("kubectl.kubernetes.io/last-applied-configuration",
					SimMeta.lastApplied(APPS_V1, "ReplicaSet", name, namespace, random.between(1_200, 6_500)));
		}
		ObjectMeta meta = SimMeta.meta("ReplicaSet", index, name, namespace, labels, annotations);
		meta.setOwnerReferences(
				List.of(SimMeta.owner(APPS_V1, "Deployment", "sim-deploy-" + index, SimMeta.uid("Deployment", index))));
		meta.setManagedFields(managed(index, labels, annotations));
		int desired = replicas(index);
		int ready = ready(index);
		return new ReplicaSetBuilder().withMetadata(meta)
			.withNewSpec()
			.withReplicas(desired)
			.withNewSelector()
			.addToMatchLabels("app", "sim")
			.addToMatchLabels("pod-template-hash", labels.get("pod-template-hash"))
			.endSelector()
			.withTemplate(template(index, labels))
			.endSpec()
			.withNewStatus()
			.withObservedGeneration(1L)
			.withReplicas(desired)
			.withFullyLabeledReplicas(desired)
			.withAvailableReplicas(ready)
			.withReadyReplicas(ready)
			.endStatus()
			.build();
	}

	/**
	 * The pod template, which is most of a workload's spec. Built here rather than
	 * reusing {@link SimPods} because a template has no status, no node and no IPs —
	 * copying a pod into it would put fields in the object that the API server strips,
	 * and a rig that serves impossible objects is the problem this whole package exists
	 * to fix.
	 */
	private static PodTemplateSpec template(int index, Map<String, String> labels) {
		SimRandom random = new SimRandom("template", index);
		PodTemplateSpecBuilder template = new PodTemplateSpecBuilder().withNewMetadata()
			.withLabels(labels)
			.addToAnnotations("checksum/config", random.hex(64))
			.addToAnnotations("prometheus.io/scrape", "true")
			.endMetadata();
		return template.withNewSpec()
			.addNewContainer()
			.withName("app")
			.withImage(IMAGE)
			.withImagePullPolicy("IfNotPresent")
			.withEnv(SimPodParts.env(index, random))
			.withNewResources()
			.addToRequests("cpu", new Quantity(random.between(10, 500) + "m"))
			.addToRequests("memory", new Quantity(random.between(32, 512) + "Mi"))
			.addToLimits("cpu", new Quantity(random.between(500, 2_000) + "m"))
			.addToLimits("memory", new Quantity(random.between(512, 2_048) + "Mi"))
			.endResources()
			.addNewVolumeMount()
			.withName("config")
			.withMountPath("/etc/sim")
			.withReadOnly(true)
			.endVolumeMount()
			.addNewPort()
			.withName("http")
			.withContainerPort(8080)
			.withProtocol("TCP")
			.endPort()
			.withTerminationMessagePath("/dev/termination-log")
			.withTerminationMessagePolicy("File")
			.endContainer()
			.addNewVolume()
			.withName("config")
			.withNewConfigMap()
			.withName("sim-config-" + index)
			.withDefaultMode(420)
			.endConfigMap()
			.endVolume()
			.withRestartPolicy("Always")
			.withDnsPolicy("ClusterFirst")
			.withSchedulerName("default-scheduler")
			.withTerminationGracePeriodSeconds(30L)
			.withServiceAccountName("sim-" + (index % 5))
			.endSpec()
			.build();
	}

	private static List<ManagedFieldsEntry> managed(int index, Map<String, String> labels,
			Map<String, String> annotations) {
		List<String> applied = new ArrayList<>(SimFields.metadataPaths(labels, annotations));
		applied.add("spec|progressDeadlineSeconds");
		applied.add("spec|replicas");
		applied.add("spec|revisionHistoryLimit");
		applied.add("spec|selector");
		applied.add("spec|strategy|.");
		applied.add("spec|strategy|rollingUpdate|.");
		applied.add("spec|strategy|rollingUpdate|maxSurge");
		applied.add("spec|strategy|rollingUpdate|maxUnavailable");
		applied.add("spec|strategy|type");
		applied.add("spec|template|metadata|.");
		applied.add("spec|template|metadata|labels|.");
		applied.add("spec|template|metadata|labels|app");
		applied.add("spec|template|metadata|labels|app.kubernetes.io/component");
		applied.add("spec|template|metadata|annotations|.");
		applied.add("spec|template|metadata|annotations|checksum/config");
		applied.add("spec|template|spec|containers|k:{\"name\":\"app\"}|volumeMounts|.");
		applied.add("spec|template|spec|dnsPolicy");
		applied.add("spec|template|spec|restartPolicy");
		applied.add("spec|template|spec|schedulerName");
		applied.add("spec|template|spec|securityContext|.");
		applied.add("spec|template|spec|serviceAccountName");
		applied.add("spec|template|spec|terminationGracePeriodSeconds");
		applied.add("spec|template|spec|volumes|.");
		applied.addAll(SimFields.containerPaths("spec|template|spec", "app"));
		String time = SimMeta.created("mf", index);
		List<String> controller = new ArrayList<>(List.of("metadata|annotations|deployment.kubernetes.io/revision",
				"status|availableReplicas", "status|observedGeneration", "status|readyReplicas", "status|replicas",
				"status|updatedReplicas", "status|conditions|.", "status|conditions|k:{\"type\":\"Available\"}|.",
				"status|conditions|k:{\"type\":\"Available\"}|status",
				"status|conditions|k:{\"type\":\"Available\"}|lastTransitionTime",
				"status|conditions|k:{\"type\":\"Available\"}|lastUpdateTime",
				"status|conditions|k:{\"type\":\"Available\"}|message",
				"status|conditions|k:{\"type\":\"Available\"}|reason",
				"status|conditions|k:{\"type\":\"Progressing\"}|.",
				"status|conditions|k:{\"type\":\"Progressing\"}|status",
				"status|conditions|k:{\"type\":\"Progressing\"}|message"));
		return List.of(SimFields.entry("helm", "Update", time, applied),
				SimFields.statusEntry("kube-controller-manager", time, controller),
				SimFields.entry("kubectl-edit", "Update", time,
						List.of("spec|replicas", "metadata|labels|app.kubernetes.io/version",
								"spec|template|spec|" + "containers|k:{\"name\":\"app\"}|image")));
	}

}
