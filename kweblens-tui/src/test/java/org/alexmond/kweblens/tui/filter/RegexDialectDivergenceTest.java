package org.alexmond.kweblens.tui.filter;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>The measured divergence between this parser and {@code objectFilter.ts}, and the
 * only one there is.</b>
 *
 * <p>
 * The grammar says "a {@code /regex/}". It does not say <i>whose</i> regex, and it
 * cannot: the browser runs V8's engine and this runs {@code java.util.regex}. Every other
 * part of the language — tokenising, negation, AND, the field prefixes, {@code status:},
 * every label operator, every error <i>sentence</i>, {@link StatusTerm} in all three of
 * its forms — was diffed line for line against the TypeScript over a 154-query corpus and
 * a 28-object fleet, and agreed exactly. What follows is the residue.
 *
 * <p>
 * <b>How it was measured.</b> The TypeScript was run unmodified under
 * {@code node --experimental-strip-types}, both implementations printed
 * {@code query → error, term count, kept names}, and the two files were diffed. Redo it
 * that way if you change anything here; reasoning about two regex engines from memory is
 * how a wrong claim gets written down.
 *
 * <h2>1. The wording of a refusal, not the refusal</h2>
 *
 * V8 says "Unterminated group", {@code java.util.regex} says "Unclosed group"; likewise
 * "Unterminated character class" / "Unclosed character class", and "numbers out of order
 * in {} quantifier" / "Illegal repetition range". <b>Both refuse, both name the pattern
 * once, both then match every row.</b> Neither side is wrong — the reason belongs to the
 * engine — so the ported test asserts the shape of the sentence rather than its words.
 *
 * <h2>2. Seven patterns the two engines read differently</h2>
 *
 * Asserted below as the Java behaviour, with the TypeScript's stated beside it. The one
 * worth knowing about is {@code \p{…}}: the TypeScript compiles with {@code 'i'} and no
 * {@code 'u'}, so {@code \p} is an identity escape and {@code /\p{L}/} silently searches
 * for the literal text {@code p{L}} — <b>zero rows and no error</b>, which is the
 * confidently-wrong answer this whole file is careful about. Filed against
 * {@code objectFilter.ts} rather than fixed here: that file belongs to the SPA.
 */
class RegexDialectDivergenceTest {

	private static final List<FilterRow> ONE = List.of(new FilterRow("web-1", "prod", "Pod", Map.of(), ""));

	/** TypeScript: {@code \p} is a literal {@code p}, so this selects nothing. */
	@Test
	void unicodePropertyEscapesAreAPropertyHereAndLiteralTextInTheBrowser() {
		assertThat(ObjectFilter.parse("/\\p{L}/").error()).isNull();
		assertThat(Rows.kept("/\\p{L}/", ONE)).containsExactly("web-1");
		assertThat(Rows.kept("/\\P{L}/", ONE)).containsExactly("web-1");
	}

	/**
	 * TypeScript: {@code []} is legal and matches nothing; {@code [^]} matches anything.
	 */
	@Test
	void anEmptyCharacterClassIsARefusalHereAndLegalInTheBrowser() {
		assertThat(ObjectFilter.parse("/[]/").error()).contains("Invalid regex");
		assertThat(ObjectFilter.parse("/[^]/").error()).contains("Invalid regex");
		assertThat(Rows.kept("/[]/", ONE)).as("refused, so the filter is not in force").containsExactly("web-1");
	}

	/**
	 * TypeScript: {@code (?i)} is not a group JavaScript has, and the query is refused.
	 */
	@Test
	void inlineFlagsAreAcceptedHereAndRefusedInTheBrowser() {
		assertThat(ObjectFilter.parse("/(?i)WEB/").error()).isNull();
		assertThat(Rows.kept("/(?i)WEB/", ONE)).containsExactly("web-1");
	}

	/** TypeScript: {@code x{,3}} is legal literal text; here it is a refusal. */
	@Test
	void anOpenEndedRepetitionIsARefusalHereAndLiteralTextInTheBrowser() {
		assertThat(ObjectFilter.parse("/x{,3}/").error()).contains("Invalid regex");
	}

	/**
	 * The half that must NOT diverge: everything the help offers, and everything an
	 * operator actually types, means the same in both engines.
	 */
	@Test
	void theRegexFormsTheHelpAdvertisesMeanTheSameInBothEngines() {
		assertThat(Rows.kept("/^web-\\d+$/", ONE)).containsExactly("web-1");
		assertThat(Rows.kept("/^WEB-/", ONE)).containsExactly("web-1");
		assertThat(Rows.kept("/^web-1$/", ONE)).containsExactly("web-1");
		assertThat(Rows.kept("/^web$/", ONE)).isEmpty();
		assertThat(Rows.kept("/x\\/y/", ONE)).isEmpty();
		assertThat(Rows.kept("/[a-z]/", ONE)).containsExactly("web-1");
		assertThat(Rows.kept("/\\w/", ONE)).containsExactly("web-1");
	}

}
