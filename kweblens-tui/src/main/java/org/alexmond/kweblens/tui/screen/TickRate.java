package org.alexmond.kweblens.tui.screen;

import java.time.Duration;

/**
 * How often the screen may repaint — and, when the operator asked for something the
 * terminal cannot do, <b>a sentence saying so</b>.
 *
 * <h2>The default is 100 ms, and here is the arithmetic</h2>
 *
 * <ul>
 * <li>A windowed <em>whole frame</em> at 2 206 rows in a 132×44 terminal measured <b>7.8
 * ms</b> in the GH#361 spike, terminal write included. At 100 ms the loop spends ~8% of
 * its time drawing and 92% available to the keyboard; at TamboUI's 40 ms default it would
 * be ~20%. (#364's own measurement of the table alone is 0.69 ms — the difference is the
 * write, which is why the frame number is the one this arithmetic uses.)</li>
 * <li>k9s's table refresh floor is <b>2 s</b>, hard-clamped. Its <em>log</em> pane, the
 * one place it is event-driven, buffers and flushes at <b>50 ms</b>. 100 ms sits between
 * them: twenty times fresher than k9s's table, half the rate of its log flush, because a
 * whole-table repaint costs more than appending lines.</li>
 * <li>{@code requestAnimationFrame} — the SPA's period — is ~16.7 ms. A terminal repaint
 * costs more than a Vue patch and a human cannot read a table that redraws sixty times a
 * second, so matching it would buy nothing and spend the budget the keyboard needs.</li>
 * <li>100 ms is also the classic threshold below which a response reads as instantaneous,
 * so a keystroke and the change it causes still land in the same perceived moment.</li>
 * </ul>
 *
 * <h2>The floor is 20 ms, and it is applied VISIBLY</h2>
 *
 * k9s silently clamps {@code refreshRate: 1} back up with a one-time warning nobody sees.
 * This does not: a clamped rate keeps {@link #requested()} and reports {@link #notice()},
 * which the screen puts in its status line for as long as the screen is up. A setting
 * that did not take effect and does not say so is worse than a setting that is refused.
 *
 * <p>
 * 20 ms is 2.5× the measured 7.8 ms frame — the fastest period at which drawing stays a
 * minority of the loop. Below it the terminal is not more live, it is only busier.
 *
 * @param period the period actually used
 * @param requested what was asked for, which is the same object unless it was clamped
 */
public record TickRate(Duration period, Duration requested) {

	/** The default period. See the class javadoc for the arithmetic. */
	public static final Duration DEFAULT = Duration.ofMillis(100);

	/** The fastest period this build will honour. */
	public static final Duration FLOOR = Duration.ofMillis(20);

	/**
	 * The slowest period this build will honour. Past ten seconds a table is not live, it
	 * is a screenshot, and the header would be claiming otherwise.
	 */
	public static final Duration CEILING = Duration.ofSeconds(10);

	/** The default rate. */
	public static TickRate defaults() {
		return new TickRate(DEFAULT, DEFAULT);
	}

	/**
	 * The rate for a requested period, clamped into {@link #FLOOR}..{@link #CEILING}. A
	 * null or non-positive request is not clamped, it is simply the default — asking for
	 * "no delay" is not asking for the floor.
	 */
	public static TickRate of(Duration requested) {
		if (requested == null || requested.isZero() || requested.isNegative()) {
			return defaults();
		}
		if (requested.compareTo(FLOOR) < 0) {
			return new TickRate(FLOOR, requested);
		}
		if (requested.compareTo(CEILING) > 0) {
			return new TickRate(CEILING, requested);
		}
		return new TickRate(requested, requested);
	}

	/** Milliseconds, for a header that has one line to say it in. */
	public long millis() {
		return this.period.toMillis();
	}

	/** Whether what is in force is not what was asked for. */
	public boolean clamped() {
		return !this.period.equals(this.requested);
	}

	/**
	 * What to show the operator: empty when the request was honoured, and otherwise a
	 * sentence naming both numbers, because "it was clamped" without the numbers is the
	 * warning k9s prints.
	 */
	public String notice() {
		if (!clamped()) {
			return "";
		}
		String direction = (this.requested.compareTo(this.period) < 0) ? "raised" : "lowered";
		return "tick " + this.requested.toMillis() + "ms " + direction + " to " + millis() + "ms";
	}

}
