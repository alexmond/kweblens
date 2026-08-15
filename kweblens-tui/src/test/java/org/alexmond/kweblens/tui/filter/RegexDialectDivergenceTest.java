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
 * <b>How it was measured.</b> Both implementations printed
 * {@code query → error, term count, kept names} for the same queries against the same
 * fleet, and the two outputs were diffed. Redo it that way if you change anything here;
 * reasoning about two regex engines from memory is how a wrong claim gets written down.
 *
 * <h2>1. The wording of a refusal, not the refusal</h2>
 *
 * V8 says "Unterminated group", {@code java.util.regex} says "Unclosed group"; likewise
 * "Unterminated character class" / "Unclosed character class", and "Incomplete
 * quantifier" / "Illegal repetition". <b>Both refuse, both name the pattern once, both
 * then match every row.</b> Neither side is wrong — the reason belongs to the engine — so
 * the ported test asserts the shape of the sentence rather than its words.
 *
 * <h2>2. The patterns the two engines read differently</h2>
 *
 * <p>
 * <b>The one that mattered is closed.</b> Until GH#410 the TypeScript compiled with
 * {@code 'i'} and no {@code 'u'}, so {@code \p} was an identity escape and
 * {@code /\p{L}/} silently searched for the literal text {@code p{L}} — zero rows and no
 * error, the confidently-wrong answer this whole file is careful about. The SPA now
 * compiles {@code 'iu'}, and a Unicode property class means the same thing on both
 * surfaces. So do case folding and {@code .}, which Unicode mode measures in code points
 * exactly as {@link java.util.regex.Pattern#UNICODE_CASE} and this engine always have.
 *
 * <p>
 * Unicode mode has no web-compatibility fallback, which trades the silent rows for loud
 * ones: <b>every divergence below is one engine REFUSING what the other accepts</b>, and
 * a refusal leaves the whole list on screen with a sentence over it. Asserted below as
 * the Java behaviour, with the TypeScript's stated beside it.
 *
 * <p>
 * <b>What is left un-measured, said out loud.</b> Two engines cannot be proved identical
 * over patterns they both accept, and at least two known differences are not about
 * escapes at all: JavaScript's {@code \s} covers Unicode spaces while Java's is the six
 * ASCII ones, and Java's {@code $} also matches before a trailing line terminator.
 * Neither can bite on what this filter reads — a name, a namespace and a kind are DNS
 * labels and identifiers, and a state label is ASCII — so they are recorded here rather
 * than asserted against fixtures that could not contain the characters in question.
 */
class RegexDialectDivergenceTest {

	private static final List<FilterRow> ONE = List.of(new FilterRow("web-1", "prod", "Pod", Map.of(), ""));

	/** Closed by GH#410: the browser reads these as property classes now, not as text. */
	@Test
	void unicodePropertyEscapesAreAPropertyOnBothSurfaces() {
		assertThat(ObjectFilter.parse("/\\p{L}/").error()).isNull();
		assertThat(Rows.kept("/\\p{L}/", ONE)).containsExactly("web-1");
		assertThat(Rows.kept("/\\P{L}/", ONE)).containsExactly("web-1");
		List<FilterRow> two = List.of(Rows.pod("web", "prod"), Rows.pod("123", "prod"));
		assertThat(Rows.kept("name:/^\\p{L}+$/", two)).as("the browser's old reading selected “p{L}”")
			.containsExactly("web");
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

	/**
	 * <b>What Unicode mode cost the browser.</b> Outside it, an escape JavaScript does
	 * not define is the character itself, so each of these compiled there and meant
	 * something other than what this engine makes of it — {@code /\A/} was a literal
	 * {@code A} in the browser and the start of the input here, and neither side said so.
	 * The SPA now refuses all of them by name ("Invalid escape", "Invalid character
	 * class", "Lone quantifier brackets"), and its sentence adds that the pattern is
	 * legal only outside Unicode mode. <b>Do not "fix" this by loosening either side</b>:
	 * the escape a {@code java.util.regex} habit reaches for is exactly the one V8 has no
	 * meaning for.
	 */
	@Test
	void escapesAndClassesThisEngineDefinesAreRefusedInTheBrowser() {
		assertThat(Rows.kept("/web\\-1/", ONE)).as("\\- is a literal hyphen here").containsExactly("web-1");
		assertThat(Rows.kept("/\\Aweb/", ONE)).as("\\A anchors at the start of the input").containsExactly("web-1");
		assertThat(Rows.kept("/\\Aeb/", ONE)).isEmpty();
		assertThat(Rows.kept("/\\Qweb-1\\E/", ONE)).as("\\Q…\\E quotes").containsExactly("web-1");
		assertThat(Rows.kept("/[\\d-a]/", ONE)).as("a class escape may end a range here").containsExactly("web-1");
		assertThat(Rows.kept("/}/", ONE)).as("a lone } is literal here").isEmpty();
		assertThat(ObjectFilter.parse("/}/").error()).isNull();
	}

	/**
	 * The other half of that trade: patterns the browser used to accept as text now fail
	 * there too, so both surfaces refuse them and only the wording differs.
	 */
	@Test
	void thePatternsBothEnginesNowRefuse() {
		assertThat(ObjectFilter.parse("/x{,3}/").error()).contains("Invalid regex").contains("Illegal repetition");
		assertThat(ObjectFilter.parse("/{/").error()).contains("Invalid regex");
		assertThat(ObjectFilter.parse("/[a-\\w]/").error()).contains("Invalid regex");
		assertThat(ObjectFilter.parse("/\\p/").error()).contains("Invalid regex");
		assertThat(Rows.kept("/x{,3}/", ONE)).as("refused, so the filter is not in force").containsExactly("web-1");
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
		assertThat(Rows.kept("/[a\\-b]/", ONE)).as("inside a class \\- is legal in both").containsExactly("web-1");
	}

}
