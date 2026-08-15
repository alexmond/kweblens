package org.alexmond.kweblens.tui.filter;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Text, negation, AND and regex — the first four {@code describe} blocks of
 * {@code objectFilter.test.ts}, case for case, with the same inputs and the same expected
 * object sets.
 */
class ObjectFilterTextTest {

	/**
	 * Not in the TypeScript corpus, and here because the Java seam has a shape the
	 * TypeScript does not: a label key may map to a null value when a manifest says
	 * {@code key:} with nothing after it. The browser sees {@code undefined} and treats
	 * the key as present with no matching value; so does this.
	 */
	@Test
	void aLabelKeyWithANullValueIsPresentButMatchesNoValue() {
		Map<String, String> labels = new LinkedHashMap<>();
		labels.put("app", null);
		FilterRow row = new FilterRow("a", "default", "Pod", labels, "");
		assertThat(ObjectFilter.parse("label:app").matches(row)).isTrue();
		assertThat(ObjectFilter.parse("app=web").matches(row)).isFalse();
		assertThat(ObjectFilter.parse("app!=web").matches(row)).isTrue();
	}

	/**
	 * The one non-negotiable: this is the common case and the box behaved this way before
	 * the grammar existed. Everything else is opt-in on a character the old filter could
	 * not have contained.
	 */
	@Nested
	class ABareWordIsWhatItAlwaysWas {

		@Test
		void matchesASubstringOfTheNameCaseInsensitively() {
			assertThat(Rows.kept("web", Rows.FLEET)).containsExactly("web-1", "web-2", "web-canary");
			assertThat(Rows.kept("WEB", Rows.FLEET)).containsExactly("web-1", "web-2", "web-canary");
		}

		@Test
		void matchesTheNamespaceAndTheKindToo() {
			assertThat(Rows.kept("kube-system", Rows.FLEET)).containsExactly("cache");
			assertThat(Rows.kept("pod", Rows.FLEET)).hasSize(Rows.FLEET.size());
		}

		@Test
		void treatsRegexMetacharactersAsLiteralText() {
			List<FilterRow> objects = List.of(Rows.pod("a.b", "default"), Rows.pod("axb", "default"));
			assertThat(Rows.kept("a.b", objects)).containsExactly("a.b");
			assertThat(Rows.kept("web*", Rows.FLEET)).isEmpty();
		}

		@Test
		void isInertWhenEmptyOrWhitespace() {
			assertThat(ObjectFilter.parse("").termCount()).isZero();
			assertThat(ObjectFilter.parse("   ").termCount()).isZero();
			assertThat(Rows.kept("   ", Rows.FLEET)).hasSize(Rows.FLEET.size());
		}

		@Test
		void survivesAnObjectWithNoNamespaceNoLabelsAndNoKind() {
			FilterRow bare = new FilterRow(null, null, null, null, null);
			assertThat(ObjectFilter.parse("web").matches(bare)).isFalse();
			assertThat(ObjectFilter.parse("-web").matches(bare)).isTrue();
			assertThat(ObjectFilter.parse("app=web").matches(bare)).isFalse();
			assertThat(ObjectFilter.parse("app!=web").matches(bare)).isTrue();
		}

	}

	@Nested
	class TermsCombineWithAndAndAnyOfThemCanBeNegated {

		@Test
		void requiresEveryTermToMatch() {
			assertThat(Rows.kept("web prod", Rows.FLEET)).containsExactly("web-1", "web-2");
			assertThat(Rows.kept("web prod nothing-like-this", Rows.FLEET)).isEmpty();
		}

		@Test
		void excludesOnALeadingDash() {
			assertThat(Rows.kept("-web", Rows.FLEET)).containsExactly("db-0", "cache");
			assertThat(Rows.kept("web -canary", Rows.FLEET)).containsExactly("web-1", "web-2");
		}

		@Test
		void negatesAnyKindOfTermNotJustText() {
			assertThat(Rows.kept("-app=web", Rows.FLEET)).containsExactly("db-0", "cache");
			assertThat(Rows.kept("-ns:prod", Rows.FLEET)).containsExactly("web-canary", "cache");
			assertThat(Rows.kept("-label:env", Rows.FLEET)).containsExactly("db-0", "cache");
		}

		@Test
		void keepsQuotedTextWithASpaceInItAsOneTerm() {
			List<FilterRow> objects = List.of(Rows.pod("two words here", "default"), Rows.pod("two", "default"),
					Rows.pod("words", "default"));
			assertThat(Rows.kept("\"two words\"", objects)).containsExactly("two words here");
			assertThat(Rows.kept("two words", objects)).containsExactly("two words here");
		}

	}

	@Nested
	class RegexIsOptInAndABrokenOneIsASentence {

		@Test
		void matchesNameNamespaceAndKindCaseInsensitively() {
			assertThat(Rows.kept("/^web-\\d+$/", Rows.FLEET)).containsExactly("web-1", "web-2");
			assertThat(Rows.kept("/^WEB-/", Rows.FLEET)).containsExactly("web-1", "web-2", "web-canary");
			assertThat(Rows.kept("/^kube-/", Rows.FLEET)).containsExactly("cache");
		}

		@Test
		void anchorsWorkWhichIsTheWholeReasonForTheFeature() {
			assertThat(Rows.kept("/^cache$/", Rows.FLEET)).containsExactly("cache");
			assertThat(Rows.kept("/^ache/", Rows.FLEET)).isEmpty();
		}

		@Test
		void reportsAnInvalidPatternInsteadOfMatchingNothing() {
			ParsedFilter filter = ObjectFilter.parse("/web(/");
			assertThat(filter.error()).startsWith("Invalid regex");
			assertThat(filter.termCount()).isZero();
			assertThat(Rows.kept("/web(/", Rows.FLEET)).hasSize(Rows.FLEET.size());
		}

		/**
		 * <b>The one measured divergence from the TypeScript, and it is the regex
		 * engine's wording, not the grammar's.</b> V8 says "Unterminated group" and
		 * java.util.regex says "Unclosed group", so the TS case asserting the whole
		 * sentence cannot be ported literally. What CAN be ported is everything that case
		 * was actually protecting: the pattern is named exactly once, and no {@code i}
		 * flag the operator never typed leaks into the message. Both engines volunteer
		 * the pattern (and V8 the flag) in their own message text; both are stripped.
		 */
		@Test
		void saysThePatternOnceWithoutTheFlagNobodyTyped() {
			String error = ObjectFilter.parse("/web(/").error();
			assertThat(error).startsWith("Invalid regex /web(/ — ").doesNotContain("/i").doesNotContain("\n");
			assertThat(error.split("web\\(", -1)).as("the pattern is named once, not twice").hasSize(2);
		}

		@Test
		void reportsAnUnterminatedPatternTheStateTheBoxIsInMidTyping() {
			assertThat(ObjectFilter.parse("/^web").error()).contains("Unterminated /regex/");
			assertThat(ObjectFilter.parse("name:/^web").error()).contains("Unterminated /regex/");
		}

		@Test
		void rejectsAnEmptyPatternRatherThanMatchingEveryRow() {
			assertThat(ObjectFilter.parse("//").error()).contains("Empty regex");
		}

		/**
		 * <b>Rule-removal control.</b> A {@code /} opens a regex only at the start of a
		 * term, after a {@code -}, or straight after a {@code prefix:}. Delete that
		 * condition from {@code FilterTokenizer.openRun} and both halves fail:
		 *
		 * <ol>
		 * <li>{@code io/nginx} starts reading a regex at the {@code /} in the middle of
		 * the word, never finds a closing one, and refuses the whole query with
		 * "Unterminated /regex/" — a name search turned into a syntax error.
		 * <li>{@code a/b c/d} is <b>two</b> terms; without the rule the run {@code /b c/}
		 * swallows the space between them, so one term is searched for literally, the
		 * fleet is filtered by a string no object carries, and the answer silently
		 * becomes "nothing matched". That is the wrong-rows failure this rule exists to
		 * prevent, not merely a wrong error message.
		 * </ol>
		 */
		@Test
		void doesNotTurnASlashInsideAWordIntoARegex() {
			List<FilterRow> objects = List.of(Rows.pod("docker.io/nginx", "default"));
			assertThat(ObjectFilter.parse("io/nginx").error()).isNull();
			assertThat(Rows.kept("io/nginx", objects)).containsExactly("docker.io/nginx");

			List<FilterRow> slashed = List.of(Rows.pod("keep-a/b", "c/d"), Rows.pod("drop-a/b", "other"));
			assertThat(ObjectFilter.parse("a/b c/d").termCount()).as("two terms, not one").isEqualTo(2);
			assertThat(Rows.kept("a/b c/d", slashed)).containsExactly("keep-a/b");
		}

		/** The other half of the same rule: where a {@code /} DOES open a regex. */
		@Test
		void opensARegexAtATermStartAfterADashAndAfterAPrefix() {
			assertThat(Rows.kept("/^web-\\d+$/", Rows.FLEET)).containsExactly("web-1", "web-2");
			assertThat(Rows.kept("-/^web-\\d+$/", Rows.FLEET)).containsExactly("db-0", "web-canary", "cache");
			assertThat(Rows.kept("name:/^web-\\d+$/", Rows.FLEET)).containsExactly("web-1", "web-2");
		}

	}

	@Nested
	class FieldTermsNarrowToOneField {

		@Test
		void matchesOnlyThatField() {
			// "prod" is in the namespace of three pods and in no name; ns: and name: must
			// disagree.
			assertThat(Rows.kept("ns:prod", Rows.FLEET)).containsExactly("web-1", "web-2", "db-0");
			assertThat(Rows.kept("name:prod", Rows.FLEET)).isEmpty();
			assertThat(Rows.kept("namespace:staging", Rows.FLEET)).containsExactly("web-canary");
			assertThat(Rows.kept("kind:pod", Rows.FLEET)).hasSize(Rows.FLEET.size());
			assertThat(Rows.kept("kind:service", Rows.FLEET)).isEmpty();
		}

		@Test
		void takesARegexOrQuotedTextAsItsValue() {
			assertThat(Rows.kept("name:/^web-\\d/", Rows.FLEET)).containsExactly("web-1", "web-2");
			assertThat(Rows.kept("ns:\"kube-system\"", Rows.FLEET)).containsExactly("cache");
		}

		@Test
		void namesAnUnknownFieldInsteadOfSearchingForAColonNoNameCanContain() {
			assertThat(ObjectFilter.parse("phase:Running").error()).contains("Unknown field “phase:”")
				.contains("name:, ns:, kind:, status: and label:");
		}

		@Test
		void rejectsAFieldPrefixWithNothingAfterIt() {
			assertThat(ObjectFilter.parse("ns:").error()).contains("Empty search text");
		}

	}

}
