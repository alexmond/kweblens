package org.alexmond.kweblens.tui.screen;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

/**
 * A clock the test moves by hand.
 *
 * <p>
 * "Stopped updating 12 s ago" and "retrying in 4 s" are numbers on screen, so they have
 * to be assertable; a test that slept for them would be slow and would still be a race.
 * The fixed reading also keeps the elapsed text still, which is what lets a test count
 * repaints exactly while the watch is down.
 */
public final class MovableClock extends Clock {

	private volatile Instant now;

	public MovableClock(Instant start) {
		this.now = start;
	}

	/** An arbitrary but fixed starting point. */
	public static MovableClock at(String iso) {
		return new MovableClock(Instant.parse(iso));
	}

	public void advance(Duration by) {
		this.now = this.now.plus(by);
	}

	@Override
	public ZoneId getZone() {
		return ZoneId.of("UTC");
	}

	@Override
	public Clock withZone(ZoneId zone) {
		return this;
	}

	@Override
	public Instant instant() {
		return this.now;
	}

}
