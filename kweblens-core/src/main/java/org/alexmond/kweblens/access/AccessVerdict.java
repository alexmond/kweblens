package org.alexmond.kweblens.access;

/**
 * What a {@code SelfSubjectAccessReview} said about one (verb, resource, namespace) — in
 * three states, because two cannot express the one that matters.
 *
 * <p>
 * <b>This is a UI affordance and never an authorization gate.</b> Per
 * {@code docs/design/adr-001-identity-model.md} (ACCEPTED) kweblens runs as a single
 * shared identity, so a review can only ever answer "can the service account this
 * deployment runs as do this" — not "can you". The server-side authorization that
 * actually protects a write is unchanged: {@code SecurityConfig} plus whatever RBAC the
 * cluster enforces on that account. Nothing may consume this type to decide whether a
 * request is allowed to proceed.
 *
 * <p>
 * <b>{@link #UNKNOWN} renders as enabled.</b> A review that errored, timed out, was
 * itself forbidden, or came back without a verdict has told us nothing — and a control
 * greyed out by a failed <i>probe</i> is a lie about the cluster. A boolean would have to
 * fold that case into one of the two real answers, which is precisely the bug this enum
 * exists to make impossible.
 */
public enum AccessVerdict {

	/** The cluster said yes. Show the control as normal. */
	ALLOWED,

	/**
	 * The cluster said no. Disable the control and say why, naming the service account
	 * rather than the operator — there is one shared identity, so there is no "you".
	 */
	DENIED,

	/**
	 * We could not tell. <b>Fails open</b>: show the control as normal and let the write
	 * be judged where it is really judged, by the API server.
	 */
	UNKNOWN

}
