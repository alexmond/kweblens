package org.alexmond.kweblens.web.helm;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The lenient SemVer comparison behind Helm chart "update available" detection. */
class HelmVersionCompareTest {

	@Test
	void newerCoreWins() {
		assertThat(HelmService.compareVersions("2.0.0", "1.9.9")).isPositive();
		assertThat(HelmService.compareVersions("1.9.9", "2.0.0")).isNegative();
	}

	@Test
	void equalVersionsAreEqual() {
		assertThat(HelmService.compareVersions("1.2.3", "1.2.3")).isZero();
	}

	@Test
	void segmentsCompareNumericallyNotLexically() {
		assertThat(HelmService.compareVersions("1.10.0", "1.2.0")).isPositive();
	}

	@Test
	void leadingVIsIgnored() {
		assertThat(HelmService.compareVersions("v2.1.0", "2.0.9")).isPositive();
	}

	@Test
	void aReleaseOutranksItsPreRelease() {
		assertThat(HelmService.compareVersions("1.0.0", "1.0.0-rc.1")).isPositive();
		assertThat(HelmService.compareVersions("1.0.0-rc.1", "1.0.0")).isNegative();
	}

	@Test
	void missingSegmentsTreatedAsZero() {
		assertThat(HelmService.compareVersions("1.2", "1.2.0")).isZero();
		assertThat(HelmService.compareVersions("1.2.1", "1.2")).isPositive();
	}

	@Test
	void nullsSortLast() {
		assertThat(HelmService.compareVersions(null, "1.0.0")).isNegative();
		assertThat(HelmService.compareVersions("1.0.0", null)).isPositive();
		assertThat(HelmService.compareVersions(null, null)).isZero();
	}

}
