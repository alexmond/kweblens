package org.alexmond.kweblens.tui.filter;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/** Malformed input produces a message, never a silently empty table. */
class ObjectFilterErrorTest {

	@ParameterizedTest
	@CsvSource(delimiter = '|',
			value = { "=web|Missing label key", "!=web|Missing label key", "label:|Missing label key", "-|bare “-”",
					"env in (|Unterminated (", "env in ()|has no values", "\"unclosed|Unterminated quote",
					"replicas>2|Numeric label requirements", "replicas<2|Numeric label requirements",
					"app!=db /bad(/|Invalid regex" })
	void isRefusedWithAnExplanation(String query, String expected) {
		ParsedFilter filter = ObjectFilter.parse(query);
		assertThat(filter.error()).contains(expected);
		assertThat(filter.termCount()).isZero();
	}

	/**
	 * The rule from #306/#316 applied to a filter: "no rows" and "your pattern is broken"
	 * are different claims, so the one that was never established is never made.
	 *
	 * <p>
	 * This is the ticket's "an unparseable query yields not-applied, here is what is
	 * wrong" in one place: the sentence is present, the term count is zero, and every row
	 * is still on screen.
	 */
	@Test
	void showsEveryRowWhileTheQueryIsBrokenRatherThanClaimingNothingMatched() {
		assertThat(Rows.kept("/bad(/", Rows.FLEET)).hasSize(Rows.FLEET.size());
		assertThat(Rows.kept("=web", Rows.FLEET)).hasSize(Rows.FLEET.size());

		ParsedFilter broken = ObjectFilter.parse("=web");
		assertThat(broken.failed()).isTrue();
		assertThat(broken.error()).isNotBlank();
		assertThat(broken.termCount()).isZero();
		for (FilterRow row : Rows.FLEET) {
			assertThat(broken.matches(row)).as("%s stays on screen", row.name()).isTrue();
		}
	}

	@Test
	void rejectsALabelKeyThatCouldNeverExist() {
		assertThat(ObjectFilter.parse(".bad=web").error()).contains("not a valid label key");
	}

	@Test
	void neverThrowsWhateverItIsHanded() {
		for (String query : List.of("((((", "////", "\"", "-/", "a=b=c=d", "in (a)", "notin", "- -", "/(?<")) {
			assertThatCode(() -> ObjectFilter.parse(query)).as("%s", query).doesNotThrowAnyException();
		}
		assertThatCode(() -> ObjectFilter.parse(null)).doesNotThrowAnyException();
	}

	/**
	 * A query that did not parse is not narrowing anything, so the header must not
	 * describe a filter: "0 of 137" over a full table would be the header contradicting
	 * what is on screen.
	 */
	@Test
	void reportsAnEmptyActiveQuerySoTheCountAndEmptyStateDoNotDescribeAFilter() {
		assertThat(ObjectFilter.parse("web").activeQuery("web")).isEqualTo("web");
		assertThat(ObjectFilter.parse("/bad(/").activeQuery("/bad(/")).isEmpty();
	}

	@Test
	void parsesEveryExampleTheHelpShows() {
		for (FilterHelp.Row row : FilterHelp.ROWS) {
			assertThat(ObjectFilter.parse(row.example()).error()).as("%s", row.example()).isNull();
		}
		assertThat(FilterHelp.ROWS).hasSize(13);
	}

	@Test
	void saysWhereTheGrammarIsKnowinglyNarrowerThanKubectl() {
		String notes = String.join(" ", FilterHelp.NOTES);
		assertThat(notes).contains("label:partition").contains("field selectors").contains("nothing is truncated");
		assertThat(notes).as("a /regex/ is the running engine's, and there are two of them")
			.contains("belongs to the engine that runs it");
		assertThat(FilterHelp.NOTES).hasSize(7);
	}

}
