package org.alexmond.kweblens.access;

/**
 * One review's outcome: the {@link AccessVerdict} and, when there is one, the sentence
 * that explains it.
 *
 * <p>
 * The reason is the cluster's own words when the cluster gave any ("RBAC: no rules
 * authorize this"), and otherwise says why we could not tell. It is never a rendered UI
 * string — the surface that disables a control writes its own copy naming the service
 * account, because only the surface knows which control it is talking about.
 *
 * @param verdict allowed / denied / unknown — see {@link AccessVerdict} for why there are
 * three
 * @param reason the cluster's stated reason, or why the answer is unknown; {@code null}
 * when there is nothing to add (an allow needs no explanation)
 */
public record AccessAnswer(AccessVerdict verdict, String reason) {

	/** The cluster said yes. */
	public static AccessAnswer allowed() {
		return new AccessAnswer(AccessVerdict.ALLOWED, null);
	}

	/** The cluster said no, with whatever reason it gave. */
	public static AccessAnswer denied(String reason) {
		return new AccessAnswer(AccessVerdict.DENIED, reason);
	}

	/**
	 * We could not tell. <b>Every failure path lands here</b>, which is what makes this
	 * fail open: see {@link AccessVerdict#UNKNOWN}.
	 */
	public static AccessAnswer unknown(String reason) {
		return new AccessAnswer(AccessVerdict.UNKNOWN, reason);
	}

	/** Whether this is a real refusal — the only verdict that may disable a control. */
	public boolean denied() {
		return this.verdict == AccessVerdict.DENIED;
	}

}
