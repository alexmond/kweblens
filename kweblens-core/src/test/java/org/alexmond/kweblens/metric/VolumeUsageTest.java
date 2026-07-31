package org.alexmond.kweblens.metric;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Whether a kubelet volume reading is actually about the claim it is labelled with.
 *
 * <p>
 * The numbers below are the ones measured on a real cluster (see
 * {@code docs/design/metrics-sources.md}), because this rule exists entirely to survive
 * them: ten NFS claims of 1Gi–30Gi all reported 3.2 TB, and four local-path claims of
 * 64Mi–10Gi all reported 41.6 GB. Without the check, every one of those would be shown
 * with a size and a percentage belonging to a shared disk.
 */
class VolumeUsageTest {

	private static final long GIB = 1024L * 1024 * 1024;

	@Test
	void acceptsAReadingThatMatchesTheRequestedSize() {
		// A real per-volume quota: reported capacity is the claim's own size.
		VolumeUsage usage = new VolumeUsage("app", "data", 8 * GIB, 10 * GIB);
		assertThat(usage.plausibleFor(10 * GIB)).isTrue();
		assertThat(usage.usedFraction()).isEqualTo(0.8);
	}

	@Test
	void toleratesTheOverheadARealVolumeHas() {
		// Filesystem overhead means a 10Gi claim does not report exactly 10Gi. The rule
		// has
		// to survive that without letting a whole-disk reading through.
		assertThat(new VolumeUsage("app", "data", 1, 9 * GIB).plausibleFor(10 * GIB)).isTrue();
		assertThat(new VolumeUsage("app", "data", 1, 12 * GIB).plausibleFor(10 * GIB)).isTrue();
	}

	@Test
	void rejectsTheNfsShareReportedForEveryClaimOnIt() {
		// Measured: 3245.7 GB reported against claims requesting 1Gi and 30Gi.
		VolumeUsage share = new VolumeUsage("app", "data", 1245L * GIB, 3245L * GIB);
		assertThat(share.plausibleFor(1 * GIB)).isFalse();
		assertThat(share.plausibleFor(30 * GIB)).isFalse();
	}

	@Test
	void rejectsTheNodeDiskReportedForLocalPathClaims() {
		// Measured: 41.6 GB reported against claims requesting 64Mi and 10Gi.
		VolumeUsage disk = new VolumeUsage("app", "cache", 16L * GIB, 41L * GIB);
		assertThat(disk.plausibleFor(64L * 1024 * 1024)).isFalse();
		assertThat(disk.plausibleFor(10 * GIB)).isFalse();
	}

	@Test
	void refusesToJudgeWithoutARequestedSizeToCompareAgainst() {
		// No baseline means no way to tell a real quota from a shared disk, and "I cannot
		// tell" must not be reported as "this is fine to display".
		VolumeUsage usage = new VolumeUsage("app", "data", 1 * GIB, 10 * GIB);
		assertThat(usage.plausibleFor(0)).isFalse();
		assertThat(usage.plausibleFor(-1)).isFalse();
	}

	@Test
	void reportsAnUnknownCapacityRatherThanDividingByZero() {
		VolumeUsage none = new VolumeUsage("app", "data", 5, 0);
		assertThat(none.usedFraction()).isEqualTo(-1);
		assertThat(none.plausibleFor(10 * GIB)).isFalse();
	}

}
