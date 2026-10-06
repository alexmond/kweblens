package org.alexmond.kweblens.web.ai;

/**
 * One diagnosis finding.
 *
 * @param severity {@code critical} / {@code warning} / {@code info}
 * @param title short problem statement
 * @param object what the finding is about, for a READER — the display string, and nothing
 * parses it. Its shape varies on purpose: {@code Pod/web-0}, {@code Pod/web-0 container
 * api}, {@code Service/api}. An earlier version of this javadoc claimed
 * {@code Kind/namespace/name}; no producer has ever emitted that, which is exactly how a
 * caller that trusted it would have built a wrong link.
 * @param detail evidence (the observed state/event that triggered the finding)
 * @param suggestedFix a concrete next step
 * @param source {@code validator} (deterministic) or {@code ai}
 * @param target the same object ADDRESSABLY, or null when the finding names nothing a
 * reader can be sent to. Separate from {@code object} because the two answer different
 * questions and one string cannot do both: a display string carries a container suffix
 * and omits the namespace, and a name may itself contain slashes.
 */
public record Finding(String severity, String title, String object, String detail, String suggestedFix, String source,
		Target target) {

	/**
	 * The object a finding is about, in the three parts it takes to reach it.
	 *
	 * <p>
	 * This exists because the UI used to have no way to build a link and could not have
	 * got one by parsing {@link Finding#object()}: there is no namespace in that string,
	 * so a namespaced object was not addressable at all, and the container suffix would
	 * have had to be stripped by a regex that each producer could invalidate
	 * independently.
	 *
	 * <p>
	 * It is a required component rather than an optional extra, so adding a producer is a
	 * compile error until its author has decided whether the finding points anywhere. A
	 * null is a real answer — a cluster-scoped audit line, or a finding about an object
	 * that was never resolved — and it renders as the plain text it always was.
	 *
	 * @param kind the Kubernetes kind, as the nav spells it ({@code Pod},
	 * {@code Service})
	 * @param namespace the namespace, or null for a cluster-scoped object
	 * @param name the object's own name, with no container suffix
	 */
	public record Target(String kind, String namespace, String name) {
	}

}
