package org.alexmond.kweblens.web.ai;

/**
 * The workload a remediation targets, as {@code Kind/name}.
 *
 * <p>
 * A pod-level action needs only a name, but every workload-level action needs the kind
 * too — a rollout restart patches a Deployment, a StatefulSet and a DaemonSet through
 * different API paths, and "scale this" is meaningless without knowing what "this" is.
 * The apply endpoint carries a single opaque {@code target} string, so the kind travels
 * inside it rather than as a second parameter.
 *
 * @param kind the Kubernetes kind ({@code Deployment}, {@code StatefulSet},
 * {@code DaemonSet})
 * @param name the object name
 */
record WorkloadRef(String kind, String name) {

	/**
	 * Parse a {@code Kind/name} target. Returns null for anything else, so a caller can
	 * reject a malformed target rather than guess at it.
	 */
	static WorkloadRef parse(String target) {
		if (target == null) {
			return null;
		}
		int slash = target.indexOf('/');
		if (slash <= 0 || slash == target.length() - 1) {
			return null;
		}
		return new WorkloadRef(target.substring(0, slash), target.substring(slash + 1));
	}

	/** The {@code Kind/name} form used as a proposal's target. */
	String ref() {
		return kind + "/" + name;
	}

}
