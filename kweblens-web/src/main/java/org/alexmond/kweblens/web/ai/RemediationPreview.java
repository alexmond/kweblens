package org.alexmond.kweblens.web.ai;

/**
 * What the cluster says an action would do — or an honest admission that it was not
 * asked.
 *
 * <p>
 * This type exists because the previous "preview" was a hand-written sentence prefixed
 * {@code dry-run:} (#209). It described what kweblens intended, which is not the same
 * claim as what the cluster would allow, and the difference is exactly what an operator
 * is being asked to approve. An admission webhook, a quota or a validation rule shows up
 * in a real dry run and in no sentence anyone writes by hand.
 *
 * <p>
 * The three outcomes are kept distinct rather than flattened into a string so a caller
 * cannot accidentally present "we did not check" as though it were "the server approved
 * this".
 *
 * @param outcome which of the three cases this is
 * @param detail the server's object as YAML for {@code SERVER_VALIDATED}, the rejection
 * message for {@code REJECTED}, or the reason no check was possible for
 * {@code NOT_CHECKED}
 */
public record RemediationPreview(Outcome outcome, String detail) {

	/** True only when the API server accepted the change under {@code dryRun=All}. */
	public boolean validated() {
		return this.outcome == Outcome.SERVER_VALIDATED;
	}

	static RemediationPreview serverValidated(String yaml) {
		return new RemediationPreview(Outcome.SERVER_VALIDATED, yaml);
	}

	static RemediationPreview rejected(String message) {
		return new RemediationPreview(Outcome.REJECTED, message);
	}

	static RemediationPreview notChecked(String why) {
		return new RemediationPreview(Outcome.NOT_CHECKED, why);
	}

	/** Whether the cluster was asked, and what it said. */
	public enum Outcome {

		/** Sent with {@code dryRun=All}; the server returned the resulting object. */
		SERVER_VALIDATED,

		/** Sent with {@code dryRun=All}; the server refused it. */
		REJECTED,

		/** Not expressible as a patch, so no dry run was possible. */
		NOT_CHECKED

	}

}
