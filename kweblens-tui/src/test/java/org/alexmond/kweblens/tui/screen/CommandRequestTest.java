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
		assertThat(CommandRequest.parse("po -f cored").error()).contains("No fuzzy matching");
		assertThat(CommandRequest.parse("po @prod").error()).contains("No context switching");
	}

	@Test
	void anEmptyLineAsksForAKind() {
		assertThat(CommandRequest.parse("   ").error()).contains("Type a kind");
		assertThat(CommandRequest.parse(null).failed()).isTrue();
	}

}
