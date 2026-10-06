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
		return new Finding("critical", "CrashLoopBackOff", "Pod/web/api", detail, "Check the logs.", "validator", null);
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
		Finding nulls = new Finding("info", "t", "o", null, null, null, null);
		Finding empties = new Finding("info", "t", "o", "", "", "", null);
		// Both canonicalise to empty strings, which is fine — what must NOT happen is a
		// finding's fields running together, so a shifted field boundary is a new key.
		assertThat(DiagnosisSummaryCache.fingerprint(List.of(nulls)))
			.isEqualTo(DiagnosisSummaryCache.fingerprint(List.of(empties)));
		assertThat(DiagnosisSummaryCache.fingerprint(List.of(new Finding("info", "to", "", "", "", "", null))))
			.isNotEqualTo(DiagnosisSummaryCache.fingerprint(List.of(new Finding("info", "t", "o", "", "", "", null))));
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

	@Test
	void tellsTwoNamespacesApartAlthoughTheirDISPLAYStringsAreIdentical() {
		// The control for hashing the target. `Finding.object` carries no namespace, so a
		// pod
		// called `web` failing the same way in two namespaces produces the same display
		// string
		// and the same everything else — identical keys, one cached summary, served for
		// whichever namespace asked second. That is this cache's stated failure mode
		// arriving
		// through a new field rather than through SHA-256, so it is asserted rather than
		// assumed: delete the three target lines from fingerprint() and this is the test
		// that
		// goes red.
		Finding prod = new Finding("critical", "CrashLoopBackOff", "Pod/web", "exit 1", "Check the logs.", "validator",
				new Finding.Target("Pod", "prod", "web"));
		Finding staging = new Finding("critical", "CrashLoopBackOff", "Pod/web", "exit 1", "Check the logs.",
				"validator", new Finding.Target("Pod", "staging", "web"));
		assertThat(prod.object()).isEqualTo(staging.object());
		assertThat(DiagnosisSummaryCache.fingerprint(List.of(prod)))
			.isNotEqualTo(DiagnosisSummaryCache.fingerprint(List.of(staging)));
	}

	@Test
	void treatsAnAbsentTargetAsItsOwnValue() {
		// A null target is a real answer (the security-audit findings have one), so it
		// must
		// hash to something stable and distinct rather than throwing or colliding with a
		// target whose parts happen to be empty strings.
		Finding none = new Finding("info", "t", "o", "d", "f", "validator", null);
		Finding empty = new Finding("info", "t", "o", "d", "f", "validator", new Finding.Target("", "", ""));
		assertThat(DiagnosisSummaryCache.fingerprint(List.of(none)))
			.isEqualTo(DiagnosisSummaryCache.fingerprint(List.of(none)));
		assertThat(DiagnosisSummaryCache.fingerprint(List.of(none)))
			.isEqualTo(DiagnosisSummaryCache.fingerprint(List.of(empty)));
	}

}
