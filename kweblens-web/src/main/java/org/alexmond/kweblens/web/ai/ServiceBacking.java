package org.alexmond.kweblens.web.ai;

/**
 * Why a Service has nothing behind it — the distinction that decides what to advise.
 *
 * <p>
 * "No endpoints" has several causes and only some are the operator's to fix, but the
 * diagnosis reported one sentence for all of them: <em>"check the Service's selector
 * against the pod labels it is meant to match"</em>. That is actively misleading when the
 * selector is correct and the workload is simply scaled to zero, because the selector is
 * then the one thing that is right.
 *
 * @param cause which situation this is
 * @param workload the single idle workload, for {@link Cause#IDLE_WORKLOAD} only
 */
public record ServiceBacking(Cause cause, WorkloadRef workload) {

	static ServiceBacking idle(WorkloadRef workload) {
		return new ServiceBacking(Cause.IDLE_WORKLOAD, workload);
	}

	static ServiceBacking of(Cause cause) {
		return new ServiceBacking(cause, null);
	}

	/** What the Service's selector found. */
	public enum Cause {

		/** Exactly one workload carries the selector and it sits at zero replicas. */
		IDLE_WORKLOAD,

		/** Nothing carries the selector — it is probably wrong. */
		NO_MATCH,

		/** Several workloads carry it, so which was meant cannot be known. */
		AMBIGUOUS,

		/** A workload carries it and is not scaled down, so the pods are the problem. */
		MATCHED_RUNNING

	}

}
