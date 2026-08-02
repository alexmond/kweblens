package org.alexmond.kweblens.web.ai;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The evidence block sent to the LLM. Hermetic — no model is called; this is about the
 * shape of the input, which is the half of the summary that is deterministic.
 *
 * <p>
 * The behaviour worth pinning: repetition collapses (a real cluster produced sixteen
 * identical "Service has nothing behind it" findings, which used to be sixteen lines of
 * prompt), the <b>count survives</b> that collapse, and a pathological cluster is capped
 * with the truncation said out loud rather than silently dropped.
 */
class DiagnosePromptInputTest {

	@Test
	void repeatedFindingsCollapseToOneGroupThatKeepsTheCount() {
		List<Finding> findings = new ArrayList<>();
		for (int i = 0; i < 16; i++) {
			findings.add(new Finding("critical", "Service has nothing behind it", "Service/svc-" + i, "no endpoints",
					"Check the selector.", "validator"));
		}

		DiagnoseService.PromptInput input = DiagnoseService.promptInput(findings);

		assertThat(input.evidence()).contains("16 findings, in 1 distinct problem groups")
			.contains("Service has nothing behind it — 16 objects")
			.contains("no endpoints (x16)")
			.contains("(+1 more)");
		// Fifteen named, one counted — the block must not grow with the cluster.
		assertThat(input.evidence()).contains("Service/svc-14").doesNotContain("Service/svc-15");
		assertThat(input.truncationNote()).isNull();
	}

	@Test
	void oneGroupWithDifferingEvidenceKeepsEachMessage() {
		List<Finding> findings = List.of(
				new Finding("critical", "Service has nothing behind it", "Service/a", "no endpoints", "fix",
						"validator"),
				new Finding("critical", "Service has nothing behind it", "Service/b", "no endpoints", "fix",
						"validator"),
				new Finding("critical", "Service has nothing behind it", "Service/c", "2 pods matched, none ready",
						"fix", "validator"));

		String evidence = DiagnoseService.promptInput(findings).evidence();

		assertThat(evidence).contains("no endpoints (x2)").contains("2 pods matched, none ready");
	}

	@Test
	void distinctProblemsStayDistinct() {
		List<Finding> findings = List.of(
				new Finding("critical", "ImagePullBackOff", "Pod/a", "bad registry", "fix", "validator"),
				new Finding("warning", "Unhealthy", "Pod/b", "probe failed", "fix", "validator"));

		String evidence = DiagnoseService.promptInput(findings).evidence();

		assertThat(evidence).contains("2 distinct problem groups")
			.contains("[critical] ImagePullBackOff — 1 object\n")
			.contains("[warning] Unhealthy — 1 object\n");
	}

	@Test
	void beyondTheCapTheLeastSevereGroupsAreDroppedAndSaidSoOutLoud() {
		List<Finding> findings = new ArrayList<>();
		for (int i = 0; i < 30; i++) {
			findings.add(new Finding("critical", "Problem " + i, "Pod/crit-" + i, "detail", "fix", "validator"));
		}
		for (int i = 0; i < 4; i++) {
			findings.add(new Finding("warning", "Later problem " + i, "Pod/warn-" + i, "detail", "fix", "validator"));
		}

		DiagnoseService.PromptInput input = DiagnoseService.promptInput(findings);

		assertThat(input.evidence()).contains("34 findings, in 34 distinct problem groups")
			.contains("[critical] Problem 24")
			.doesNotContain("[critical] Problem 25")
			.doesNotContain("Later problem")
			.contains("(9 further, less severe groups covering 9 findings are not listed here.)");
		assertThat(input.truncationNote())
			.isEqualTo("Summarised from the 25 most severe of 34 findings; " + "the rest were not sent to the model.");
	}

	@Test
	void aPartialEvidenceListSaysItIsPartial() {
		List<Finding> findings = new ArrayList<>();
		for (int i = 0; i < 6; i++) {
			findings.add(new Finding("warning", "Unhealthy", "Pod/p-" + i,
					"Readiness probe failed: dial tcp 10.42.0." + i + ":9999", "fix", "validator"));
		}

		String evidence = DiagnoseService.promptInput(findings).evidence();

		// Shown a sample with no sign it was a sample, the model reported the port those
		// messages named as the port all six pods were failing on.
		assertThat(evidence).contains("evidence (4 of 6 distinct messages):");
	}

	@Test
	void aLongSchedulerMessageIsAbbreviatedRatherThanSentWhole() {
		String message = "0/4 nodes are available: ".repeat(40);
		List<Finding> findings = List
			.of(new Finding("critical", "Unschedulable", "Pod/a", message, "fix", "validator"));

		String evidence = DiagnoseService.promptInput(findings).evidence();

		assertThat(evidence).contains("…(truncated)");
		assertThat(evidence.length()).isLessThan(message.length());
	}

	@Test
	void aFindingWithNoDetailStillRenders() {
		List<Finding> findings = List.of(new Finding("info", "Something", "Pod/a", null, null, "validator"));

		assertThat(DiagnoseService.promptInput(findings).evidence()).contains("evidence: (no detail)");
	}

}
