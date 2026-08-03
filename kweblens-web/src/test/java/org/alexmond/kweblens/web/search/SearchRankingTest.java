package org.alexmond.kweblens.web.search;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The ranking tiers, pinned so the ordering people see cannot drift silently.
 */
class SearchRankingTest {

	@Test
	void prefixBeatsWordBoundaryBeatsSubstring() {
		int prefix = SearchRanking.score("sonar", "sonarqube-abc");
		int word = SearchRanking.score("sonar", "ci-sonarqube-abc");
		int inner = SearchRanking.score("sonar", "cisonarqubeabc");
		assertThat(prefix).isGreaterThan(word);
		assertThat(word).isGreaterThan(inner);
	}

	@Test
	void shorterNameWinsInsideATier() {
		assertThat(SearchRanking.score("pg", "pg-0")).isGreaterThan(SearchRanking.score("pg", "pg-primary-0"));
	}

	@Test
	void noMatchIsNegative() {
		assertThat(SearchRanking.score("redis", "sonarqube-0")).isNegative();
		assertThat(SearchRanking.score("", "sonarqube-0")).isNegative();
		assertThat(SearchRanking.score("x", null)).isNegative();
	}

	/**
	 * The weakest tier is a substring, not the client's subsequence. Over object names a
	 * subsequence matches nearly everything, which is the same as matching nothing.
	 */
	@Test
	void weakestTierIsSubstringNotSubsequence() {
		assertThat(SearchRanking.score("abc", "alpha-beta-charlie")).isNegative();
	}

	/**
	 * The kind bonus breaks ties inside a tier without crossing one: a Pod the query is a
	 * prefix of still outranks a Deployment matched only at a word boundary.
	 */
	@Test
	void kindBonusDoesNotOutweighMatchQuality() {
		int generatedPrefix = SearchRanking.score("sonar", "sonar-7d9-x2k") + SearchKinds.bonus("pods");
		int primaryWord = SearchRanking.score("sonar", "ci-sonar") + SearchKinds.bonus("deployments");
		assertThat(generatedPrefix).isGreaterThan(primaryWord);
	}

	@Test
	void primaryKindsCarryTheBonusAndGeneratedOnesDoNot() {
		assertThat(SearchKinds.bonus("deployments")).isEqualTo(SearchRanking.PRIMARY_KIND_BONUS);
		assertThat(SearchKinds.bonus("pods")).isZero();
		assertThat(SearchKinds.ids()).contains("pods", "deployments", "services", "secrets")
			.doesNotContain("replicasets");
	}

}
