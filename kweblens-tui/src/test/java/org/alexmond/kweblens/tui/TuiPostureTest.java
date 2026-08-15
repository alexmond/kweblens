package org.alexmond.kweblens.tui;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v1 is read-only, and the header says so.
 *
 * <p>
 * The assertion on {@link TuiPosture#current()} is the point: when writes are added, this
 * test fails, and changing it is the moment someone has to have decided that the TUI can
 * now change a cluster. That is cheaper than discovering the badge was still claiming
 * {@code [R]} afterwards.
 */
class TuiPostureTest {

	@Test
	void thisBuildIsReadOnly() {
		assertThat(TuiPosture.current()).isEqualTo(TuiPosture.READ_ONLY);
		assertThat(TuiPosture.current().badge()).isEqualTo("[R]");
		assertThat(TuiPosture.current().description()).isEqualTo("read-only");
	}

	@Test
	void theBadgeIsAClaimBecauseTheOtherAnswerExists() {
		assertThat(TuiPosture.READ_WRITE.badge()).isEqualTo("[RW]");
		assertThat(TuiPosture.READ_WRITE.badge()).isNotEqualTo(TuiPosture.READ_ONLY.badge());
	}

}
