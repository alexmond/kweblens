package org.alexmond.kweblens.health;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;

/**
 * What one pod's own spec says about the privileges its containers hold.
 *
 * <p>
 * Everything here is decided from the pod object alone — it is already listed for the
 * workload checks, so this dimension costs no cluster request at all.
 *
 * <p>
 * <b>Only what the manifest states is reported.</b> A container with no {@code runAsUser}
 * and no {@code runAsNonRoot} very often runs as root, but the object does not say so:
 * the answer is in the image's {@code USER} directive, which is not in the API. Reporting
 * that as "runs as root" would be a guess wearing the clothes of an observation, and a
 * finding nobody can check against the object it names is a bug. So the absent case is
 * silent and only an explicit {@code runAsUser: 0} is a finding.
 *
 * <p>
 * Severity is deliberately below the workload failures. A privileged container is nearly
 * always a deliberate choice — a CNI agent, a CSI driver, a node exporter — so putting it
 * at {@code critical} would bury an ImagePullBackOff under a list of things that are
 * working as intended. That was the #223 lesson, and it applies here with more force,
 * because these findings appear on a cluster where nothing is broken at all.
 */
final class PodSecurity {

	private static final String PRIVILEGED = "Container runs privileged";

	private static final String ROOT = "Container runs as root";

	private PodSecurity() {
	}

	/** Findings for one pod. Empty for a pod that claims no special privileges. */
	static List<SecurityFinding> forPod(GenericKubernetesResource pod) {
		Map<String, Object> spec = map(pod.getAdditionalProperties().get("spec"));
		String name = WorkloadHealth.nameOf(pod);
		Integer podUser = user(map(spec.get("securityContext")));
		List<SecurityFinding> findings = new ArrayList<>();
		scan(findings, name, spec.get("initContainers"), true, podUser);
		scan(findings, name, spec.get("containers"), false, podUser);
		return findings;
	}

	private static void scan(List<SecurityFinding> findings, String pod, Object containers, boolean init,
			Integer podUser) {
		for (Map<String, Object> container : list(containers)) {
			String name = str(container.get("name"));
			Map<String, Object> context = map(container.get("securityContext"));
			String where = where(pod, name, init);
			if (Boolean.TRUE.equals(context.get("privileged"))) {
				findings.add(new SecurityFinding("warning", PRIVILEGED, where,
						"securityContext.privileged=true on container '" + name
								+ "' — it holds every capability the kernel has, and can reconfigure the node",
						"Drop privileged and add back only the capabilities the process needs,"
								+ " or confirm this container is meant to manage the node."));
			}
			// The container's own setting wins over the pod's, so a pod-level 0 that
			// every container overrides is not a finding — that is exactly the shape a
			// careless join would report.
			Integer user = (user(context) != null) ? user(context) : podUser;
			if (user != null && user == 0) {
				findings.add(new SecurityFinding("info", ROOT, where,
						"securityContext.runAsUser=0 (" + ((user(context) != null) ? "on the container" : "on the pod")
								+ ") — container '" + name + "' runs as UID 0",
						"Set runAsUser to a non-zero UID, and runAsNonRoot: true so the kubelet refuses"
								+ " an image that would still start as root."));
			}
		}
	}

	/** Named the same way {@code PodDiagnosis} names a container, so both read alike. */
	private static String where(String pod, String container, boolean init) {
		if (container == null || container.isBlank()) {
			return "Pod/" + pod;
		}
		return "Pod/" + pod + (init ? " init container " : " container ") + container;
	}

	private static Integer user(Map<String, Object> context) {
		return (context.get("runAsUser") instanceof Number n) ? n.intValue() : null;
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> map(Object value) {
		return (value instanceof Map) ? (Map<String, Object>) value : Map.of();
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> list(Object value) {
		return (value instanceof List<?> l) ? (List<Map<String, Object>>) l : List.of();
	}

	private static String str(Object value) {
		return (value != null) ? value.toString() : null;
	}

}
