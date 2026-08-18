package org.alexmond.kweblens.tui.screen;

import org.junit.jupiter.api.Test;

import org.alexmond.kweblens.tui.filter.ObjectFilter;

import static org.assertj.core.api.Assertions.assertThat;

/** Taking a {@code :} line apart, and refusing the parts this build does not have. */
class CommandRequestTest {

	@Test
	void aBareKindIsAKindAndNothingElse() {
		CommandRequest request = CommandRequest.parse("pods");

		assertThat(request.failed()).isFalse();
		assertThat(request.kind()).isEqualTo("pods");
		assertThat(request.namespace()).isNull();
		assertThat(request.filter()).isEmpty();
	}

	@Test
	void aSecondTokenShapedLikeANamespaceIsOne() {
		CommandRequest request = CommandRequest.parse("po kube-system");

		assertThat(request.namespace()).isEqualTo("kube-system");
		assertThat(request.filter()).isEmpty();
	}

	@Test
	void allMeansEveryNamespaceRatherThanANamespaceCalledAll() {
		assertThat(CommandRequest.parse("po all").namespace()).isNull();
	}

	@Test
	void aSecondTokenThatCannotBeANamespaceIsTheStartOfTheFilter() {
		CommandRequest request = CommandRequest.parse("po app=web");

		assertThat(request.namespace()).as("a namespace has no = in it").isNull();
		assertThat(request.filter()).isEqualTo("app=web");
	}

	@Test
	void k9sWritesTheFilterWithALeadingSlashAndThisGrammarDoesNot() {
		assertThat(CommandRequest.parse("po kube-system /coredns").filter()).isEqualTo("coredns");
		assertThat(CommandRequest.parse("po kube-system /^web-\\d+$/").filter())
			.as("a regex keeps BOTH its slashes; only k9s's single leading one is dropped")
			.isEqualTo("/^web-\\d+$/");
	}

	@Test
	void whatADrillDownWritesIsALineThisGrammarAndTheFilterGrammarBothRead() {
		CommandRequest request = CommandRequest.parse("pods kube-system k8s-app=kube-dns");

		assertThat(request.filter()).isEqualTo("k8s-app=kube-dns");
		assertThat(ObjectFilter.parse(request.filter()).failed())
			.as("the query a drill-down produces must parse in this product's own filter language")
			.isFalse();
	}

	@Test
	void theHalvesOfK9ssGrammarThisBuildLacksAreRefusedInWordsAndNotIgnored() {
		assertThat(CommandRequest.parse("po -f cored").failed()).isTrue();
		assertThat(CommandRequest.parse("po @prod").error()).contains("No context switching");
	}

	/**
	 * <b>A refusal names the form that exists</b> (GH#469).
	 *
	 * <p>
	 * {@code -f} is still refused, because it is a <em>flag</em> this grammar has no
	 * position for and because {@code -f} is already a legal term here meaning "not f".
	 * What it must not do is deny the capability: #411 put {@code ~fuzzy} in both
	 * implementations, so a sentence sending the operator to a substring sends them
	 * further from what they asked for than the answer they wanted.
	 *
	 * <p>
	 * The alternative is named <b>and proved</b>. The sentence quotes the term that was
	 * typed, and the filter parser is asked whether that spelling really works — a
	 * refusal naming a form nothing accepts is the same defect one spelling over.
	 */
	@Test
	void theRefusalOfMinusFNamesTheFuzzySpellingThisBuildDoesHave() {
		String error = CommandRequest.parse("po -f cored").error();

		assertThat(error).as("the refusal must carry the working spelling of what was asked for").contains("~cored");
		assertThat(error).as("and must not deny a capability this build has had since #411")
			.doesNotContain("No fuzzy matching");
		assertThat(ObjectFilter.parse("~cored").failed()).as("and the spelling it names has to parse").isFalse();
	}

	/** With nothing after it there is no term to quote, so the shape is named instead. */
	@Test
	void theRefusalStillNamesTheShapeWhenThereIsNothingToQuote() {
		assertThat(CommandRequest.parse("po -f").error()).contains("~");
	}

	@Test
	void anEmptyLineAsksForAKind() {
		assertThat(CommandRequest.parse("   ").error()).contains("Type a kind");
		assertThat(CommandRequest.parse(null).failed()).isTrue();
	}

}
