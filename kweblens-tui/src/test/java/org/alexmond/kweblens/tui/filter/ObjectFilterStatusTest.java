package org.alexmond.kweblens.tui.filter;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code status:} — the term GH#337 exists for, ported case for case.
 *
 * <p>
 * Its ONE requirement is that a state an overview counted is selectable and selects
 * exactly those objects, so most of what follows is about the ways a looser match would
 * quietly break that equality rather than fail loudly.
 */
class ObjectFilterStatusTest {

	private static List<FilterRow> judged() {
		List<FilterRow> judged = new ArrayList<>();
		for (FilterRow row : Rows.STATED) {
			if (!row.state().isEmpty()) {
				judged.add(row);
			}
		}
		return judged;
	}

	private static Set<String> labelsOf(List<FilterRow> rows) {
		Set<String> labels = new LinkedHashSet<>();
		for (FilterRow row : rows) {
			labels.add(row.state());
		}
		return labels;
	}

	private static List<String> namesInState(List<FilterRow> rows, String state) {
		List<String> names = new ArrayList<>();
		for (FilterRow row : rows) {
			if (row.state().equals(state)) {
				names.add(row.name());
			}
		}
		return names;
	}

	@Nested
	class SelectsOnTheStateTheServerComputed {

		@Test
		void matchesTheStateLabelCaseInsensitively() {
			assertThat(Rows.kept("status:Running", Rows.STATED)).containsExactly("web-1", "web-2");
			assertThat(Rows.kept("status:running", Rows.STATED)).containsExactly("web-1", "web-2");
			assertThat(Rows.kept("status:PENDING", Rows.STATED)).containsExactly("sched-1");
		}

		/**
		 * The failure this term is built to prevent. As a substring match, "Complete"
		 * would take "Completed" too — an overview saying 1 Complete opening a list of 2.
		 * The stem pair is in the fleet precisely so a regression here fails.
		 */
		@Test
		void matchesTheWholeLabelSoOneStateCannotDragAnotherInWithIt() {
			assertThat(Rows.kept("status:Complete", Rows.STATED)).containsExactly("job-run");
			assertThat(Rows.kept("status:Completed", Rows.STATED)).containsExactly("job-done");
			assertThat(Rows.kept("status:Backoff", Rows.STATED)).isEmpty();
			assertThat(Rows.kept("status:Run", Rows.STATED)).isEmpty();
		}

		@Test
		void takesAQuotedValueBecauseAStateCanHaveASpaceInIt() {
			assertThat(Rows.kept("status:\"No endpoints\"", Rows.STATED)).containsExactly("svc-1");
		}

		@Test
		void takesARegexForTheGenuinelyFuzzyQuestion() {
			assertThat(Rows.kept("status:/backoff/", Rows.STATED)).containsExactly("crash-1", "pull-1");
			assertThat(Rows.kept("status:/^Complete$/", Rows.STATED)).containsExactly("job-run");
		}

		@Test
		void negatesLikeEveryOtherTerm() {
			assertThat(Rows.kept("-status:Running", Rows.STATED)).containsExactly("job-done", "job-run", "sched-1",
					"crash-1", "pull-1", "svc-1", "unjudged");
		}

		@Test
		void andsWithTheRestOfTheGrammar() {
			assertThat(Rows.kept("status:Running name:web-1", Rows.STATED)).containsExactly("web-1");
			assertThat(Rows.kept("status:Running ns:staging", Rows.STATED)).isEmpty();
		}

		/**
		 * "unjudged" carries no state — an uncovered kind. No pattern may claim it, not
		 * even one that matches everything, because absence of a verdict is not a state.
		 */
		@Test
		void neverSelectsARowTheServerReachedNoVerdictAbout() {
			assertThat(Rows.kept("status:Running", Rows.STATED)).doesNotContain("unjudged");
			assertThat(Rows.kept("status:/.*/", Rows.STATED)).doesNotContain("unjudged");
			assertThat(Rows.kept("status:/^$/", Rows.STATED)).doesNotContain("unjudged");
			// ...and the negation keeps it, which is the same fact stated the other way.
			assertThat(Rows.kept("-status:Running", Rows.STATED)).contains("unjudged");
		}

		@Test
		void ignoresAStateTheServerSentEmptyRatherThanCountingItAsOne() {
			List<FilterRow> blank = List.of(new FilterRow("blank", "", "Pod", Map.of(), ""));
			assertThat(Rows.kept("status:/.*/", blank)).isEmpty();
		}

		@ParameterizedTest
		@CsvSource(delimiter = '|', value = { "status:|Missing state after “status:”",
				"status:\"\"|Missing state after “status:”", "status://|Empty regex", "status:/bad(/|Invalid regex" })
		void isRefusedWithAnExplanationAndLeavesEveryRowOnScreen(String query, String expected) {
			ParsedFilter filter = ObjectFilter.parse(query);
			assertThat(filter.error()).contains(expected);
			assertThat(filter.termCount()).isZero();
			assertThat(Rows.kept(query, Rows.STATED)).hasSize(Rows.STATED.size());
		}

		/**
		 * The ticket's equality, expressed without a server: for each distinct label the
		 * rows carry — which is exactly the set of labels an overview tallies — the query
		 * selects the rows with that label and nothing else, and the selections add up to
		 * the judged population.
		 */
		@Test
		void partitionsAFleetExactlyEveryStateSelectsItsOwnObjectsAndNoOthers() {
			List<FilterRow> judged = judged();
			int selected = 0;
			for (String label : labelsOf(judged)) {
				List<String> expected = namesInState(judged, label);
				assertThat(Rows.kept("status:\"" + label + "\"", Rows.STATED)).isEqualTo(expected);
				selected += expected.size();
			}
			assertThat(selected).isEqualTo(judged.size());
		}

	}

	/**
	 * The writer's half of {@code status:} (#338). A state line is selectable only
	 * because a query can be BUILT from the label, and the number equals the rows it
	 * opens only while the writing and the reading agree — so every case here is a round
	 * trip, never a string compared with a string someone typed into the test.
	 */
	@Nested
	class StatusTermWritesWhatTheMatcherReads {

		@Test
		void roundTripsEveryStateInAFleetBackToExactlyItsOwnObjects() {
			List<FilterRow> judged = judged();
			for (String label : labelsOf(judged)) {
				assertThat(Rows.kept(StatusTerm.query(label), Rows.STATED)).isEqualTo(namesInState(judged, label));
			}
		}

		@Test
		void leavesABareStateBareAndQuotesOnlyWhatWouldOtherwiseSplitIntoTwoTerms() {
			assertThat(StatusTerm.query("Running")).isEqualTo("status:Running");
			assertThat(StatusTerm.query("CrashLoopBackOff")).isEqualTo("status:CrashLoopBackOff");
			assertThat(StatusTerm.query("No endpoints")).isEqualTo("status:\"No endpoints\"");
		}

		@Test
		void roundTripsTheStemPairWhichASubstringMatchWouldCollapse() {
			assertThat(Rows.kept(StatusTerm.query("Complete"), Rows.STATED)).containsExactly("job-run");
			assertThat(Rows.kept(StatusTerm.query("Completed"), Rows.STATED)).containsExactly("job-done");
		}

		/**
		 * The Cluster overview's vocabulary (#339) is kubectl's: a cordoned node is
		 * {@code Ready,SchedulingDisabled}, one term with a comma in it. Two ways this
		 * could go wrong and both would be silent — the comma splitting the term, or the
		 * exact match slipping so that {@code status:Ready} also selects the cordoned
		 * node, which is counted on a different line.
		 */
		@Test
		void keepsKubectlsCommaBearingNodeStateWholeAndApartFromThePlainOne() {
			List<FilterRow> nodes = List.of(new FilterRow("worker-1", "", "Node", Map.of(), "Ready"),
					new FilterRow("worker-3", "", "Node", Map.of(), "Ready,SchedulingDisabled"),
					new FilterRow("worker-4", "", "Node", Map.of(), "NotReady"));

			assertThat(StatusTerm.query("Ready,SchedulingDisabled")).isEqualTo("status:Ready,SchedulingDisabled");
			assertThat(Rows.kept(StatusTerm.query("Ready,SchedulingDisabled"), nodes)).containsExactly("worker-3");
			assertThat(Rows.kept(StatusTerm.query("Ready"), nodes)).containsExactly("worker-1");
			assertThat(Rows.kept(StatusTerm.query("NotReady"), nodes)).containsExactly("worker-4");
		}

		/**
		 * There is no escape character, so a {@code "} inside a quoted value ends the
		 * quote. The honest answer is that such a state has no query — the caller renders
		 * it as text — because the alternative is a selection whose rows are not the ones
		 * that were counted.
		 */
		@Test
		void refusesALabelTheGrammarCannotExpressRatherThanWritingOneThatMeansSomethingElse() {
			assertThat(StatusTerm.queryable("Running")).isTrue();
			assertThat(StatusTerm.queryable("No endpoints")).isTrue();
			assertThat(StatusTerm.queryable("say \"hi\"")).isFalse();
			assertThat(StatusTerm.queryable("")).isFalse();
			assertThat(StatusTerm.queryable("   ")).isFalse();
			assertThat(StatusTerm.queryable(null)).isFalse();
		}

		/**
		 * WorkloadHealth's whole vocabulary, plus the waiting reasons a pod's state is
		 * named after. A term that does not select its own label back is a number offered
		 * and then answered with zero rows.
		 */
		@Test
		void roundTripsEveryStateTheCoveredKindsCanBeIn() {
			for (String label : List.of("Running", "Completed", "Pending", "CrashLoopBackOff", "ImagePullBackOff",
					"ErrImagePull", "Healthy", "Unavailable", "Idle", "Ready", "Active", "Succeeded", "Failed",
					"Suspended", "Scheduled", "Unknown")) {
				String query = StatusTerm.withStatusTerm("", label);
				assertThat(ObjectFilter.parse(query).error()).as("%s", label).isNull();
				assertThat(Rows.kept(query, List.of(Rows.stated("hit", label), Rows.stated("miss", "Something else"))))
					.as("%s", label)
					.containsExactly("hit");
			}
		}

	}

}
