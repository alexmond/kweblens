package org.alexmond.kweblens.tui.filter;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Label requirements follow apimachinery, including the part people get wrong.
 *
 * <p>
 * {@code labels.Requirement.Matches}: {@code In}/{@code Equals} need the key present;
 * {@code NotIn}/{@code NotEquals} are TRUE when the key is absent. The upstream docs say
 * it in words — "environment!=production … also selects resources with no environment
 * label" — and a filter that quietly disagreed would give a different answer from the
 * {@code kubectl} the operator ran a minute ago.
 *
 * <p>
 * <b>On not using apimachinery's parser.</b> There isn't one to use: fabric8 7.3.1 ships
 * {@code LabelSelector} and {@code LabelSelectorRequirement} as <i>data model</i> types
 * and no string parser or evaluator anywhere in {@code kubernetes-client},
 * {@code kubernetes-client-api} or {@code kubernetes-model-core} (grepped, jar by jar).
 * The Go {@code labels.Parse}/{@code Requirement.Matches} pair has no Java equivalent on
 * this classpath, so the semantics are transcribed here and pinned by these cases
 * instead.
 */
class ObjectFilterLabelTest {

	@Test
	void equalityNeedsTheKeyToBePresent() {
		assertThat(Rows.kept("app=web", Rows.FLEET)).containsExactly("web-1", "web-2", "web-canary");
		assertThat(Rows.kept("app==web", Rows.FLEET)).containsExactly("web-1", "web-2", "web-canary");
		assertThat(Rows.kept("app=nope", Rows.FLEET)).isEmpty();
	}

	@Test
	void inequalityAlsoKeepsObjectsThatCarryNoSuchLabel() {
		// cache has no app label at all and must survive app!=web.
		assertThat(Rows.kept("app!=web", Rows.FLEET)).containsExactly("db-0", "cache");
		assertThat(Rows.kept("env!=prod", Rows.FLEET)).containsExactly("db-0", "web-canary", "cache");
	}

	@Test
	void inNeedsTheKeyNotinAlsoKeepsObjectsWithoutIt() {
		assertThat(Rows.kept("env in (prod,staging)", Rows.FLEET)).containsExactly("web-1", "web-2", "web-canary");
		assertThat(Rows.kept("env in (staging)", Rows.FLEET)).containsExactly("web-canary");
		assertThat(Rows.kept("env notin (prod)", Rows.FLEET)).containsExactly("db-0", "web-canary", "cache");
	}

	/**
	 * <b>Rule-removal control for the absent-key rule.</b> Every row that satisfies these
	 * queries satisfies them <i>only</i> because it has no such key: there is no row that
	 * carries the key with a different value, so a version of {@code Atom.Label#matches}
	 * that returned false for an absent key — the natural, wrong implementation — answers
	 * both with an empty list and this fails.
	 *
	 * <p>
	 * The corpus cases above pass either way for {@code db-0}, which carries
	 * {@code app=db}; that is exactly why this control exists beside them.
	 */
	@Test
	void notinAndNotEqualsMatchAnObjectThatDoesNotCarryTheKeyAtAll() {
		List<FilterRow> objects = List.of(Rows.pod("has-it", "default", Map.of("app", "web")),
				Rows.pod("lacks-it", "default", Map.of("other", "x")));

		assertThat(Rows.kept("app!=web", objects)).as("!= keeps the object with no app key")
			.containsExactly("lacks-it");
		assertThat(Rows.kept("app notin (web)", objects)).as("notin keeps the object with no app key")
			.containsExactly("lacks-it");
		assertThat(Rows.kept("app!=anything-at-all", objects)).as("and it is the ABSENCE that matches, not the value")
			.containsExactly("has-it", "lacks-it");

		// The positive control that stops this passing on a broken probe: the same key,
		// asked the other way round, must NOT keep the object that lacks it.
		assertThat(Rows.kept("app=web", objects)).containsExactly("has-it");
		assertThat(Rows.kept("app in (web)", objects)).containsExactly("has-it");
		assertThat(Rows.kept("label:app", objects)).containsExactly("has-it");
	}

	@Test
	void acceptsTheSpacingKubectlAccepts() {
		List<String> expected = List.of("web-1", "web-2", "web-canary");
		assertThat(Rows.kept("env in (prod, staging)", Rows.FLEET)).isEqualTo(expected);
		assertThat(Rows.kept("env in(prod,staging)", Rows.FLEET)).isEqualTo(expected);
		assertThat(Rows.kept("env  in  ( prod , staging )", Rows.FLEET)).isEqualTo(expected);
	}

	@Test
	void negatesASetRequirementAsAWhole() {
		// The `-` binds to the term, and the term is re-joined from three
		// whitespace-separated pieces — so this is the one place where the splitter and
		// the negation have to agree.
		assertThat(Rows.kept("-env in (prod)", Rows.FLEET)).containsExactly("db-0", "web-canary", "cache");
		assertThat(Rows.kept("-label:app=web", Rows.FLEET)).containsExactly("db-0", "cache");
	}

	@Test
	void comparesLabelValuesExactlyCaseAndAllLikeKubectl() {
		List<FilterRow> objects = List.of(Rows.pod("a", "default", Map.of("app", "Web")),
				Rows.pod("b", "default", Map.of("app", "web")));
		assertThat(Rows.kept("app=web", objects)).containsExactly("b");
		assertThat(Rows.kept("app=Web", objects)).containsExactly("a");
	}

	@Test
	void supportsTheEmptyValueTheSelectorGrammarAllows() {
		List<FilterRow> objects = List.of(Rows.pod("a", "default", Map.of("app", "")),
				Rows.pod("b", "default", Map.of("app", "web")));
		assertThat(Rows.kept("app=", objects)).containsExactly("a");
		assertThat(Rows.kept("app!=", objects)).containsExactly("b");
	}

	@Test
	void handlesPresenceAndAbsenceThroughTheLabelPrefix() {
		assertThat(Rows.kept("label:env", Rows.FLEET)).containsExactly("web-1", "web-2", "web-canary");
		assertThat(Rows.kept("-label:env", Rows.FLEET)).containsExactly("db-0", "cache");
		assertThat(Rows.kept("label:app=web", Rows.FLEET)).containsExactly("web-1", "web-2", "web-canary");
	}

	@Test
	void matchesAPrefixedLabelKey() {
		List<FilterRow> objects = List.of(Rows.pod("a", "default", Map.of("example.com/team", "core")),
				Rows.pod("b", "default", Map.of()));
		assertThat(Rows.kept("example.com/team=core", objects)).containsExactly("a");
		assertThat(Rows.kept("label:example.com/team", objects)).containsExactly("a");
	}

	@Test
	void combinesSeveralRequirementsAsLabelSelectorDoes() {
		assertThat(Rows.kept("app=web env=prod", Rows.FLEET)).containsExactly("web-1", "web-2");
		assertThat(Rows.kept("app=web -env=prod", Rows.FLEET)).containsExactly("web-canary");
		assertThat(Rows.kept("tier in (frontend) ns:prod", Rows.FLEET)).containsExactly("web-1", "web-2");
	}

}
