package org.alexmond.kweblens.tui.screen;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.alexmond.kweblens.tui.data.WatchEnd;

/**
 * Whether the table is still live, <b>in words</b>, and the one thing that puts it back:
 * the answer to GH#413.
 *
 * <h2>The defect this exists for</h2>
 *
 * A watch dies — etcd compaction, a proxy's idle timeout, an API-server restart — and the
 * terminal goes on drawing the last state it saw, with a row count and a tick period that
 * both read as current. Nothing on screen changes, so there is nothing to notice; the
 * operator reads a photograph and believes it is a feed. That is the same failure as a
 * number about the cluster you just left (#323): wrong, and plausible.
 *
 * <h2>Both endings are loud, and they are told apart</h2>
 *
 * A clean end and a failure produce the same posture — <b>NOT LIVE</b>, since nothing
 * further arrives either way — and the same response, a reconnect. They differ in the
 * sentence, because the sentence is the only clue the signal carries. What never reaches
 * here at all is a close the TUI asked for; {@code CoreClusterDataSource} suppresses
 * that, so quitting is silent and, more importantly, the reconnect's own close of the
 * previous handle is not read as a fresh loss.
 *
 * <h2>Recovery is a decision, not an implication</h2>
 *
 * <ul>
 * <li><b>It recovers.</b> Re-subscribe, then re-list, then replace the rows — a re-list
 * is the only honest recovery because the TUI tracks no {@code resourceVersion}.</li>
 * <li><b>Exactly one attempt is ever in flight</b>, enforced by {@link #attempting}
 * rather than by hoping the network is quick. A watch that keeps dying therefore produces
 * one open watch at a time, never a stack of them.</li>
 * <li><b>It never gives up</b>, but it backs off — {@link #FIRST_RETRY} doubling to
 * {@link #MAX_RETRY} — so a cluster that is down is retried at a rate that says so rather
 * than hammered. Because it never gives up, the notice never has to tell the operator to
 * restart the app; it says what is happening and when the next attempt is.</li>
 * </ul>
 *
 * <h2>Threading</h2>
 *
 * Three threads touch this class and each has one job. The <b>watch thread</b> calls the
 * consumer from {@link #listener()} and does nothing but stamp an {@link AtomicReference}
 * — the same discipline {@link WatchCoalescer#offer} keeps, and for the same measured
 * reason. The <b>recovery thread</b> runs {@link WatchRestart#reconnect} — the two calls
 * that block on a network — and stamps its result. The <b>render thread</b> owns
 * everything else, inside {@link #tick()}: it is the only thread that reads or writes the
 * fields below and the only one that touches {@link ResourceModel}. Doing the reconnect
 * on the tick instead would have been shorter and would have parked the keyboard for a
 * client timeout every time a dead API server was retried.
 *
 * <h2>Stale reports cannot be mistaken for new ones</h2>
 *
 * Each subscription is minted with a generation. An ending reported by a subscription
 * that is no longer the current one is dropped, so a handle that fails while it is being
 * replaced cannot register as the failure of its own replacement — which would have
 * looked exactly like a reconnect loop.
 */
public class WatchSupervisor implements AutoCloseable {

	/**
	 * The gap before the <em>second</em> attempt. The first is made on the tick that
	 * notices the loss, because most losses are a stream that dropped rather than a
	 * cluster that went away, and waiting a second to find that out would be a second of
	 * a table labelled NOT LIVE that was never in danger.
	 */
	static final Duration FIRST_RETRY = Duration.ofSeconds(1);

	/** The longest gap between attempts. */
	static final Duration MAX_RETRY = Duration.ofSeconds(30);

	private static final int BACKOFF_FACTOR = 2;

	private final Clock clock;

	private final ResourceModel model;

	private final WatchRestart restart;

	/** Written by the watch thread, drained by the render thread. */
	private final AtomicReference<WatchEnd> reported = new AtomicReference<>();

	/** Written by the recovery thread, drained by the render thread. */
	private final AtomicReference<Outcome> outcome = new AtomicReference<>();

	/**
	 * Which subscription is current. Minted by whichever thread is opening one — the main
	 * thread for the first subscribe, before the loop starts, and the render thread for
	 * every reconnect after it — and read by the watch thread, which is why it is not a
	 * plain field.
	 */
	private final AtomicLong generation = new AtomicLong();

	/**
	 * Created on the first loss, so a screen that never loses one never starts a thread.
	 */
	private ExecutorService recovery;

	private WatchEnd loss;

	private Instant lostAt;

	private Instant nextAttemptAt;

	private Duration backoff = FIRST_RETRY;

	private boolean attempting;

	private int attempts;

	private int failures;

	private int recoveries;

	private String lastFailure;

	/**
	 * The whole seconds last shown, so the elapsed text repaints once a second, not every
	 * tick.
	 */
	private long shownSeconds = -1;

	public WatchSupervisor(Clock clock, ResourceModel model, WatchRestart restart) {
		this.clock = clock;
		this.model = model;
		this.restart = restart;
	}

	/**
	 * Mint one new subscription. The generation it carries is the only one whose ending
	 * will be believed <em>and</em> the only one whose events the buffer will keep once a
	 * later subscription has replaced it — see {@link WatchLease}.
	 */
	public WatchLease lease() {
		long minted = this.generation.incrementAndGet();
		return new WatchLease(minted, (end) -> ended(minted, end));
	}

	/**
	 * Just the ending half of a {@link #lease()}, for a caller that opens no watch and so
	 * has no events to stamp. Package-private on purpose: everything that does open one
	 * needs the generation as well, and taking the shorter method would be how an event
	 * loses the identity that tells it from a superseded watch's (GH#417).
	 */
	Consumer<WatchEnd> listener() {
		return lease().onEnd();
	}

	/** Called on the watch thread. Stamps a reference and returns; nothing else. */
	private void ended(long minted, WatchEnd end) {
		if (minted == this.generation.get()) {
			this.reported.compareAndSet(null, end);
		}
	}

	/**
	 * One tick's worth of supervision, on the render thread.
	 * @return whether the screen owes a repaint — because the posture changed, because a
	 * recovery replaced the rows, or because the "how long ago" moved on a second
	 */
	public boolean tick() {
		boolean repaint = adoptOutcome();
		repaint |= adoptLoss();
		if (this.loss == null) {
			return repaint;
		}
		Instant now = this.clock.instant();
		if (!this.attempting && !now.isBefore(this.nextAttemptAt)) {
			startAttempt();
			repaint = true;
		}
		repaint |= elapsedChanged(now);
		return repaint;
	}

	/**
	 * Take up a reported ending, unless one is already being worked on. A second loss
	 * while the first is still unresolved is the same loss as far as the screen is
	 * concerned.
	 */
	private boolean adoptLoss() {
		if (this.loss != null) {
			return false;
		}
		WatchEnd fresh = this.reported.getAndSet(null);
		if (fresh == null) {
			return false;
		}
		this.loss = fresh;
		this.lostAt = this.clock.instant();
		this.nextAttemptAt = this.lostAt;
		this.backoff = FIRST_RETRY;
		this.shownSeconds = -1;
		return true;
	}

	/** Install what the recovery thread produced, or record why it could not. */
	private boolean adoptOutcome() {
		Outcome done = this.outcome.getAndSet(null);
		if (done == null) {
			return false;
		}
		this.attempting = false;
		if (done.rows() == null) {
			this.failures++;
			this.lastFailure = message(done.failure());
			// Wait the current gap, then widen it — so the gaps are FIRST_RETRY, twice
			// that, four times, rather than starting at the doubled one.
			this.nextAttemptAt = this.clock.instant().plus(this.backoff);
			this.backoff = next(this.backoff);
			return true;
		}
		this.model.replaceAll(done.rows());
		this.recoveries++;
		this.loss = null;
		this.lastFailure = null;
		return true;
	}

	private void startAttempt() {
		this.attempting = true;
		this.attempts++;
		WatchLease lease = lease();
		recovery().execute(() -> reconnect(lease));
	}

	/**
	 * On the recovery thread. Every exit stamps {@link #outcome}, or the screen would
	 * wait forever.
	 */
	private void reconnect(WatchLease lease) {
		try {
			this.outcome.set(new Outcome(List.copyOf(this.restart.reconnect(lease)), null));
		}
		catch (RuntimeException ex) {
			this.outcome.set(new Outcome(null, ex));
		}
	}

	private ExecutorService recovery() {
		if (this.recovery == null) {
			this.recovery = Executors.newSingleThreadExecutor((task) -> {
				Thread thread = new Thread(task, "kweblens-tui-watch-recovery");
				thread.setDaemon(true);
				return thread;
			});
		}
		return this.recovery;
	}

	private boolean elapsedChanged(Instant now) {
		long seconds = Duration.between(this.lostAt, now).toSeconds();
		if (seconds == this.shownSeconds) {
			return false;
		}
		this.shownSeconds = seconds;
		return true;
	}

	private static Duration next(Duration current) {
		Duration doubled = current.multipliedBy(BACKOFF_FACTOR);
		return (doubled.compareTo(MAX_RETRY) > 0) ? MAX_RETRY : doubled;
	}

	private static String message(RuntimeException cause) {
		String message = cause.getMessage();
		return (message == null || message.isBlank()) ? cause.getClass().getSimpleName() : message.replace('\n', ' ');
	}

	/**
	 * What the header says, or empty while the watch is live.
	 *
	 * <p>
	 * It leads with <b>NOT LIVE</b> rather than appending a note, because the first thing
	 * on the line is the thing that survives a narrow terminal, and because everything
	 * after it — the row count especially — is now a claim about a moment that has
	 * passed.
	 */
	public String notice() {
		if (this.loss == null) {
			return "";
		}
		Instant now = this.clock.instant();
		StringBuilder line = new StringBuilder(128);
		line.append("NOT LIVE · ")
			.append(this.loss.summary())
			.append(" · stopped updating ")
			.append(Duration.between(this.lostAt, now).toSeconds())
			.append("s ago · ");
		if (this.attempting) {
			return line.append("reconnecting (attempt ").append(this.attempts).append(')').toString();
		}
		if (this.lastFailure != null) {
			line.append("reconnect failed: ").append(this.lastFailure).append(" · ");
		}
		long wait = Math.max(0, Duration.between(now, this.nextAttemptAt).toSeconds());
		return line.append("retrying in ").append(wait).append('s').toString();
	}

	/** Whether the table on screen is being kept up to date. */
	public boolean live() {
		return this.loss == null;
	}

	/**
	 * Whether a finished reconnect is sitting here waiting for a tick to install it.
	 *
	 * <p>
	 * It exists for one test. GH#417 is an <b>ordering</b>: an event that lands in the
	 * buffer after the last flush and before the replacement rows are installed. A test
	 * that fires the event and hopes is not a race test — it passes on the benign
	 * interleaving, which is most of them — so the test reads this to know the rows are
	 * ready, fires into the dead watch, and then ticks once.
	 */
	public boolean recoveryPending() {
		return this.outcome.get() != null;
	}

	/**
	 * How many reconnects have been started, ever. One per loss, plus one per failed
	 * retry.
	 */
	public int attempts() {
		return this.attempts;
	}

	/** How many reconnects the cluster refused. */
	public int failures() {
		return this.failures;
	}

	/** How many times the screen went from NOT LIVE back to live. */
	public int recoveries() {
		return this.recoveries;
	}

	@Override
	public void close() {
		if (this.recovery != null) {
			this.recovery.shutdownNow();
			this.recovery = null;
		}
	}

	/**
	 * What one reconnect produced: the whole kind's rows, or the exception that stopped
	 * it. Exactly one is null, which is why they are not two references anyone has to
	 * keep consistent.
	 */
	private record Outcome(List<ResourceRow> rows, RuntimeException failure) {
	}

}
