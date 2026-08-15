package org.alexmond.kweblens.tui.screen;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The period, and the sentence a clamped period owes the operator.
 *
 * <p>
 * k9s clamps {@code refreshRate: 1} back up to its 2 s floor with a one-time warning that
 * scrolls away; the setting looks applied and is not. The claim under test here is that
 * this build's clamp is <em>visible</em> — {@link TickRate#notice()} names both numbers
 * and the screen carries it in the header for as long as the screen is up.
 */
class TickRateTest {

	@Test
	void theDefaultIsOneHundredMillisecondsAndIsNotClamped() {
		TickRate rate = TickRate.defaults();

		assertThat(rate.millis()).isEqualTo(100);
		assertThat(rate.clamped()).isFalse();
		assertThat(rate.notice()).isEmpty();
	}

	@Test
	void aPeriodBelowTheFloorIsRaisedAndSaysSoWithBothNumbers() {
		TickRate rate = TickRate.of(Duration.ofMillis(1));

		assertThat(rate.period()).isEqualTo(TickRate.FLOOR);
		assertThat(rate.requested()).isEqualTo(Duration.ofMillis(1));
		assertThat(rate.clamped()).isTrue();
		assertThat(rate.notice()).isEqualTo("tick 1ms raised to 20ms");
	}

	@Test
	void aPeriodAboveTheCeilingIsLoweredAndSaysSo() {
		TickRate rate = TickRate.of(Duration.ofMinutes(1));

		assertThat(rate.period()).isEqualTo(TickRate.CEILING);
		assertThat(rate.clamped()).isTrue();
		assertThat(rate.notice()).isEqualTo("tick 60000ms lowered to 10000ms");
	}

	@Test
	void aPeriodInsideTheRangeIsHonouredExactly() {
		TickRate rate = TickRate.of(Duration.ofMillis(250));

		assertThat(rate.millis()).isEqualTo(250);
		assertThat(rate.clamped()).isFalse();
	}

	@Test
	void askingForNothingIsTheDefaultRatherThanTheFloor() {
		assertThat(TickRate.of(null)).isEqualTo(TickRate.defaults());
		assertThat(TickRate.of(Duration.ZERO)).isEqualTo(TickRate.defaults());
		assertThat(TickRate.of(Duration.ofMillis(-5))).isEqualTo(TickRate.defaults());
	}

}
