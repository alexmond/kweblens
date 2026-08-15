package org.alexmond.kweblens.tui.filter;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The parser's inverse (GH#341): what selecting a state does to the query.
 *
 * <p>
 * Every case is about the query still saying the truth afterwards — the query is the one
 * mechanism that owns filtering, so a rewrite that loses a term hides rows nobody asked
 * to hide.
 */
class StatusTermTest {

	@Test
	void addsOneToAnEmptyQuery() {
		assertThat(StatusTerm.withStatusTerm("", "Running")).isEqualTo("status:Running");
	}

	/**
	 * Terms are ANDed and the grammar has no "either", so two status terms select
	 * NOTHING. Something that appended would answer its second selection with an empty
	 * table.
	 */
	@Test
	void replacesThePositiveStatusTermRatherThanAndingASecondOne() {
		assertThat(StatusTerm.withStatusTerm("status:Running", "Pending")).isEqualTo("status:Pending");
		assertThat(Rows.kept("status:Running status:Pending", Rows.STATED)).isEmpty();
	}

	@Test
	void keepsEveryOtherTermWhereverTheStatusTermSat() {
		assertThat(StatusTerm.withStatusTerm("ns:prod status:Running -web app=x", "Pending"))
			.isEqualTo("ns:prod -web app=x status:Pending");
	}

	/**
	 * The reason this lives beside the parser: knowing where one term ends is the
	 * tokenizer's knowledge, and a second splitter written next to a caller is a copy
	 * that goes stale.
	 */
	@Test
	void keepsQuotedTextRegexesAndValueListsWhole() {
		assertThat(StatusTerm.withStatusTerm("name:\"two words\" /^web-\\d+$/ env in (dev,stage)", "Pending"))
			.isEqualTo("name:\"two words\" /^web-\\d+$/ env in (dev,stage) status:Pending");
	}

	/**
	 * "everything except the healthy ones" ANDs with a positive term perfectly well, and
	 * dropping it would silently widen the list under the operator.
	 */
	@Test
	void leavesANegatedStatusTermAloneItIsADifferentQuestion() {
		assertThat(StatusTerm.withStatusTerm("-status:Running", "Pending")).isEqualTo("-status:Running status:Pending");
		assertThat(StatusTerm.withStatusTerm("-status:Running", null)).isEqualTo("-status:Running");
		assertThat(Rows.kept("-status:Running status:Pending", Rows.STATED)).containsExactly("sched-1");
	}

	@Test
	void removesTheTermWhenGivenNoLabel() {
		assertThat(StatusTerm.withStatusTerm("ns:prod status:Running", null)).isEqualTo("ns:prod");
		assertThat(StatusTerm.withStatusTerm("status:Running", null)).isEmpty();
	}

	/**
	 * A bare {@code status:No endpoints} is two terms selecting nothing. No workload
	 * state has a space today; the kinds GH#336 goes on to cover do.
	 */
	@Test
	void quotesAStateWhoseNameHasASpaceInItAndTheTermSelectsItBack() {
		String query = StatusTerm.withStatusTerm("", "No endpoints");
		assertThat(query).isEqualTo("status:\"No endpoints\"");
		assertThat(Rows.kept(query, Rows.STATED)).containsExactly("svc-1");
	}

	/**
	 * Half-typed, not broken forever: rewriting the string someone is in the middle of
	 * typing would move the caret out from under them, and there is no term structure
	 * there to edit.
	 */
	@Test
	void returnsAQueryItCannotEvenTokenizeUnchanged() {
		assertThat(StatusTerm.withStatusTerm("name:\"half typed", "Running")).isEqualTo("name:\"half typed");
	}

}
