package org.alexmond.kweblens.web.ai;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The cache key, which is the whole point of the mechanism (#251): a summary comes back
 * only for the findings it was written about.
 */
class DiagnosisSummaryCacheTest {

	private static Finding finding(String detail) {
		return new Finding("critical", "CrashLoopBackOff", "Pod/web/api", detail, "Check the logs.", "validator");
	}

	@Test
	void oneChangedFieldIsADifferentKey() {
		String before = DiagnosisSummaryCache.fingerprint(List.of(finding("exit code 1")));
		assertThat(DiagnosisSummaryCache.fingerprint(List.of(finding("exit code 1")))).isEqualTo(before);
		assertThat(DiagnosisSummaryCache.fingerprint(List.of(finding("exit code 137")))).isNotEqualTo(before);
		assertThat(DiagnosisSummaryCache.fingerprint(List.of())).isNotEqualTo(before);
	}

	@Test
	void orderMattersBecauseTheModelSeesTheListInOrder() {
		Finding first = finding("exit code 1");
		Finding second = finding("exit code 137");
		assertThat(DiagnosisSummaryCache.fingerprint(List.of(first, second)))
			.isNotEqualTo(DiagnosisSummaryCache.fingerprint(List.of(second, first)));
	}

	@Test
	void nullFieldsDoNotCollideWithEmptyOnes() {
		Finding nulls = new Finding("info", "t", "o", null, null, null);
		Finding empties = new Finding("info", "t", "o", "", "", "");
		// Both canonicalise to empty strings, which is fine — what must NOT happen is a
		// finding's fields running together, so a shifted field boundary is a new key.
		assertThat(DiagnosisSummaryCache.fingerprint(List.of(nulls)))
			.isEqualTo(DiagnosisSummaryCache.fingerprint(List.of(empties)));
		assertThat(DiagnosisSummaryCache.fingerprint(List.of(new Finding("info", "to", "", "", "", ""))))
			.isNotEqualTo(DiagnosisSummaryCache.fingerprint(List.of(new Finding("info", "t", "o", "", "", ""))));
	}

	@Test
	void aSummaryIsServedOnlyForTheFindingsItWasWrittenAbout() {
		DiagnosisSummaryCache cache = new DiagnosisSummaryCache();
		String before = DiagnosisSummaryCache.fingerprint(List.of(finding("exit code 1")));
		String after = DiagnosisSummaryCache.fingerprint(List.of(finding("exit code 137")));
		cache.put("c1", "web", before, "Restart the api pod.", 1);

		assertThat(cache.find("c1", "web", before)).isNotNull()
			.extracting(DiagnosisSummaryCache.CachedSummary::summary)
			.isEqualTo("Restart the api pod.");
		assertThat(cache.find("c1", "web", after)).isNull();
		assertThat(cache.find("c1", "other", before)).isNull();
		assertThat(cache.find("c2", "web", before)).isNull();
	}

	@Test
	void aScopeThatMovedOnReportsWhenItWasLastAnalysed() {
		DiagnosisSummaryCache cache = new DiagnosisSummaryCache();
		String before = DiagnosisSummaryCache.fingerprint(List.of(finding("exit code 1")));
		String after = DiagnosisSummaryCache.fingerprint(List.of(finding("exit code 137")));
		cache.put("c1", "web", before, "Restart the api pod.", 1);

		// A usable summary is not "superseded", and a never-analysed scope is not either
		// —
		// only the case the panel needs to explain: analysed once, no longer applicable.
		assertThat(cache.supersededAt("c1", "web", before)).isNull();
		assertThat(cache.supersededAt("c1", "never", after)).isNull();
		assertThat(cache.supersededAt("c1", "web", after)).isNotNull();
	}

	@Test
	void aClusterWideScopeIsNotTheSameAsANamespacedOne() {
		DiagnosisSummaryCache cache = new DiagnosisSummaryCache();
		String key = DiagnosisSummaryCache.fingerprint(List.of(finding("exit code 1")));
		cache.put("c1", null, key, "Whole-cluster reading.", 1);
		assertThat(cache.find("c1", null, key)).isNotNull();
		assertThat(cache.find("c1", "web", key)).isNull();
	}

	@Test
	void theLeastRecentlyUsedScopeIsEvicted() {
		DiagnosisSummaryCache cache = new DiagnosisSummaryCache();
		String key = DiagnosisSummaryCache.fingerprint(List.of(finding("exit code 1")));
		for (int i = 0; i < 200; i++) {
			cache.put("c1", "ns" + i, key, "summary " + i, 1);
		}
		// The oldest is gone (an extra inference call, never a wrong answer); the newest
		// is still there.
		assertThat(cache.find("c1", "ns0", key)).isNull();
		assertThat(cache.find("c1", "ns199", key)).isNotNull();
	}

}
