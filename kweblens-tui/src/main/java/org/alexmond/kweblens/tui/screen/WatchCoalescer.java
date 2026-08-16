package org.alexmond.kweblens.tui.screen;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiConsumer;

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;

/**
 * The TUI's half of this project's coalescing rule: <b>a burst of watch events becomes at
 * most one repaint per tick.</b>
 *
 * <p>
 * The SPA's half is {@code useResourceData}, which buffers into a map keyed by
 * {@code namespace/name} and applies it once per animation frame; its gate fires 157
 * {@code ADDED} events — the real ReplicaSet count that once froze a tab — and asserts
 * one {@code objects} update. A terminal has no animation frame, so the period is a tick,
 * and the gate is {@code WatchCoalescerTest}, assertion for assertion.
 *
 * <h2>Why not simply repaint per event</h2>
 *
 * Measured on this stack (GH#361 spike, 2 000 events, 132×44): buffered gave <b>2
 * renders</b>; the per-event control gave 223 renders in four seconds <em>and never
 * processed the keystroke at all</em> — the app would not quit. TamboUI's
 * {@code pollEvent} deprioritises only {@code TickEvent}; a redraw posted with
 * {@code runLater} is a {@code UiRunnable}, which is FIFO with real keys. So repainting
 * per event does not merely cost frames, <b>it starves the keyboard</b>, and frame rate
 * alone cannot see that. This class therefore never posts anything: {@link #offer}
 * touches a map and returns.
 *
 * <h2>What a tick that finds nothing costs</h2>
 *
 * A map check. {@link #flush()} returns {@link Flush#IDLE} and the screen owes no
 * repaint, so a backlog of ticks queued behind a slow frame collapses to one repaint
 * rather than one per tick — k9s drops the overrunning tick outright and loses the
 * refresh; here the work is already in the buffer and the next tick applies it.
 *
 * <h2>Whose events these are</h2>
 *
 * The buffer belongs to <b>one subscription at a time</b>, named by {@link #rebase} and
 * carried into every event by {@link #sink}. An event from an older subscription is
 * refused rather than buffered, and {@link #rebase} throws away what the subscription it
 * replaces had left behind. That is GH#417: a recovery is re-subscribe, re-list,
 * {@code replaceAll}, and until the buffer could tell the two watches apart, an event the
 * dying one delivered on its way out was applied on top of the row the fresh list had
 * just corrected — one row quietly older than the table around it, on a screen that had
 * just finished saying it was live again. It self-corrected on the next event <em>for
 * that object</em>, which is not a bound: a row that stops changing keeps the stale value
 * until the next full recovery.
 *
 * <h2>And when they may be applied</h2>
 *
 * Refusing the old subscription's events was only half of it. The <b>new</b>
 * subscription's events are open for business from the moment it is opened, while its own
 * list is still on the network — and a tick in that interval used to flush them onto the
 * rows the list is about to replace, so the {@code replaceAll} that installed it wiped
 * them. An event newer than the list was therefore <em>lost</em>, with exactly the
 * unbounded self-correction above, in the opposite direction. That is GH#420, and it is
 * why {@link #rebase} does not merely name the new subscription but <b>holds</b> its
 * events: they are staged, not buffered, until {@link #installed} says that
 * subscription's own list is in the model, and are promoted onto it rather than under it.
 *
 * <p>
 * So the two halves are one sentence: <b>a subscription's events are kept only from the
 * moment it is opened, and applied only after its own list has landed.</b>
 *
 * <p>
 * It costs a tick nothing. The discrimination is one {@code long} comparison per event
 * and one field read that picks which of two maps the event goes into, both made under
 * the lock {@link #offer} already takes; a reconnect costs two map clears and one map
 * move whose size is bounded by the number of distinct objects that changed while the
 * list was in flight. There is nothing per-event that walks anything, which #364's 100 ms
 * tick could not have afforded.
 *
 * <h2>Threading</h2>
 *
 * {@link #offer} runs on fabric8's watch thread; {@link #flush()} runs on the render
 * thread; {@link #rebase} runs on whichever thread is opening a subscription — the main
 * thread for the first, the supervisor's recovery thread for every reconnect after it.
 * The buffer lock is what makes that safe, and it is why the generation check is made
 * <em>inside</em> it: checked outside, an event refused by a rebase that had not happened
 * yet would still be put into the buffer the rebase had already emptied. The
 * compare-and-set is the second guard: a flush that is already running refuses a second
 * one ({@link Flush#DROPPED}) instead of interleaving two batches into the model — and
 * the refused events stay buffered, so nothing is lost, it is only later.
 */
public class WatchCoalescer {

	/** fabric8's action names; anything else is treated as an upsert, not ignored. */
	private static final String DELETED = "DELETED";

	private final ReentrantLock lock = new ReentrantLock();

	/**
	 * Pending changes keyed by {@code namespace/name}, in arrival order. <b>The map is
	 * the coalescing</b>: {@code ADDED a}, {@code MODIFIED a} is one entry carrying the
	 * modified object, and {@code ADDED b}, {@code DELETED b} is one entry that removes a
	 * row that was never drawn.
	 */
	private final Map<String, Pending> pending = new LinkedHashMap<>();

	/**
	 * The same map, for the subscription whose list has not been installed yet —
	 * coalesced the same way and by the same key, and invisible to {@link #flush()} until
	 * {@link #installed} moves it across. Its size is bounded by the number of distinct
	 * objects that changed while the list was in flight, which is at worst the number of
	 * objects the list itself is carrying.
	 */
	private final Map<String, Pending> staged = new LinkedHashMap<>();

	private final AtomicBoolean flushing = new AtomicBoolean();

	private final AtomicLong received = new AtomicLong();

	private final AtomicLong flushes = new AtomicLong();

	private final AtomicLong dropped = new AtomicLong();

	private final AtomicLong stale = new AtomicLong();

	private final ResourceModel model;

	private final RowBatch projection;

	/**
	 * Which subscription this buffer holds events for. Guarded by {@link #lock}, because
	 * the answer has to be the same one an {@link #offer} and a {@link #rebase} racing
	 * each other agree on.
	 */
	private long baseline;

	/**
	 * Whether {@link #baseline}'s own list is still in flight, and therefore whether its
	 * events go to {@link #staged} rather than {@link #pending}. Guarded by {@link #lock}
	 * for the same reason {@link #baseline} is. <b>False until the first
	 * {@link #rebase}</b>: a buffer nobody has pointed at a subscription is not waiting
	 * for anybody's list, which is what the in-package coalescing tests use.
	 */
	private boolean awaitingList;

	public WatchCoalescer(ResourceModel model, RowBatch projection) {
		this.model = model;
		this.projection = projection;
	}

	/**
	 * The event consumer for one subscription — the only way production feeds this
	 * buffer, because it is the only one that cannot arrive without saying which watch it
	 * came from.
	 * @param generation the subscription's identity, from {@link WatchLease}, captured
	 * here and <b>not</b> read again when an event arrives — see {@link WatchLease} for
	 * why the difference is the whole defect
	 * @return what {@code ClusterDataSource.watch} takes as its event consumer
	 */
	public BiConsumer<String, GenericKubernetesResource> sink(long generation) {
		return (action, object) -> offer(generation, action, object);
	}

	/**
	 * Point the buffer at a new subscription: from here on its events are the ones kept,
	 * and everything the subscription it replaces had left buffered is thrown away.
	 *
	 * <p>
	 * Called when a subscription is <b>opened</b>, not when its list lands, and that is
	 * the ordering the fix turns on. A reconnect closes the dead handle, rebases, opens
	 * the replacement and only then re-lists; so an event the dead watch had already
	 * buffered is discarded here, an event it delivers afterwards is refused by
	 * {@link #offer}, and the list taken next is newer than both. Discarding is safe
	 * precisely because a re-list follows: the buffered change is in the rows that are
	 * about to replace the model anyway.
	 *
	 * <p>
	 * It also <b>starts the hold</b>: until {@link #installed} is called with this same
	 * generation, what this subscription delivers is staged rather than buffered, because
	 * a flush that applied it would be applying it to rows the subscription's own list is
	 * about to replace (GH#420). Every caller therefore owes an {@link #installed} —
	 * {@code ScreenSession} pairs the two, and a rebase whose list never lands leaves a
	 * table that says NOT LIVE and does not move, which is the truthful posture rather
	 * than a lost update.
	 * @param generation the identity of the subscription now feeding this buffer
	 */
	public void rebase(long generation) {
		this.lock.lock();
		try {
			this.baseline = generation;
			this.pending.clear();
			this.staged.clear();
			this.awaitingList = true;
		}
		finally {
			this.lock.unlock();
		}
	}

	/**
	 * Say that {@code generation}'s own list is now in the model, so what it delivered
	 * while that list was in flight may be applied — <b>on top of the list, on the next
	 * flush</b>, which is the ordering the whole recovery turns on.
	 *
	 * <p>
	 * A generation below the current baseline is a list belonging to a subscription that
	 * has since been replaced; releasing the hold on <em>its</em> word would let the
	 * replacement's events out before the replacement's own list had landed, so it is
	 * ignored. That is the same rule {@link #offer} applies to events, one level up.
	 * @param generation the subscription whose list has been installed
	 */
	public void installed(long generation) {
		this.lock.lock();
		try {
			if (generation < this.baseline) {
				return;
			}
			// pending is empty here in every reachable ordering — rebase cleared it and
			// nothing has been buffered since — so this is a move, not a merge. putAll is
			// still the right call: staged is newer than anything pending could hold.
			this.pending.putAll(this.staged);
			this.staged.clear();
			this.awaitingList = false;
		}
		finally {
			this.lock.unlock();
		}
	}

	/**
	 * Absorb one watch event that carries no subscription identity, stamped with whatever
	 * the buffer's baseline currently is and therefore never stale.
	 *
	 * <p>
	 * Package-private, and it is the enforcement rather than a convenience: the
	 * coalescing tests live in this package and have one watch and no reconnect, while
	 * {@code ScreenSession} lives in another and can only reach {@link #sink}. There is
	 * no way to wire a subscription to this buffer and lose its identity on the way.
	 */
	void offer(String action, GenericKubernetesResource object) {
		this.lock.lock();
		try {
			offer(this.baseline, action, object);
		}
		finally {
			this.lock.unlock();
		}
	}

	/**
	 * Absorb one watch event. Called from the watch thread, and it must stay cheap: no
	 * projection, no verdict, no repaint, no UI work posted anywhere — and the one
	 * addition GH#417 makes is a {@code long} comparison, inside the lock this already
	 * took.
	 * @param generation which subscription delivered it
	 * @param action fabric8's action name — {@code ADDED}, {@code MODIFIED},
	 * {@code DELETED}
	 * @param object the object the event carried
	 */
	private void offer(long generation, String action, GenericKubernetesResource object) {
		if (object == null) {
			return;
		}
		String key = ResourceRow.keyOf(object);
		this.lock.lock();
		try {
			if (generation < this.baseline) {
				// A watch that has already been replaced, still talking. What it has to
				// say
				// is older than the list that replaced it.
				this.stale.incrementAndGet();
				return;
			}
			this.received.incrementAndGet();
			// One field read decides which map: kept either way, applied only once this
			// subscription's own list is in the model (GH#420).
			Map<String, Pending> into = (this.awaitingList) ? this.staged : this.pending;
			into.put(key, new Pending(DELETED.equals(action), object));
		}
		finally {
			this.lock.unlock();
		}
	}

	/**
	 * Apply everything buffered since the last flush, as one batch.
	 * @return {@link Flush#APPLIED} when the model changed and a repaint is owed,
	 * {@link Flush#IDLE} when nothing was buffered, {@link Flush#DROPPED} when another
	 * flush was already running
	 */
	public Flush flush() {
		if (!this.flushing.compareAndSet(false, true)) {
			this.dropped.incrementAndGet();
			return Flush.DROPPED;
		}
		try {
			List<Pending> batch = drain();
			if (batch.isEmpty()) {
				return Flush.IDLE;
			}
			this.flushes.incrementAndGet();
			return apply(batch) ? Flush.APPLIED : Flush.IDLE;
		}
		finally {
			this.flushing.set(false);
		}
	}

	private List<Pending> drain() {
		this.lock.lock();
		try {
			if (this.pending.isEmpty()) {
				return List.of();
			}
			List<Pending> batch = new ArrayList<>(this.pending.values());
			this.pending.clear();
			return batch;
		}
		finally {
			this.lock.unlock();
		}
	}

	/**
	 * One projection call for the whole batch — one {@code StatusContext}, not one per
	 * row — and one model mutation for the deletes.
	 */
	private boolean apply(List<Pending> batch) {
		List<GenericKubernetesResource> upserts = new ArrayList<>(batch.size());
		List<String> deletes = new ArrayList<>();
		for (Pending change : batch) {
			if (change.deleted()) {
				deletes.add(ResourceRow.keyOf(change.object()));
			}
			else {
				upserts.add(change.object());
			}
		}
		boolean changed = this.model.remove(deletes);
		if (!upserts.isEmpty()) {
			changed |= this.model.upsert(this.projection.project(upserts));
		}
		return changed;
	}

	/** How many events have been offered, ever. */
	public long received() {
		return this.received.get();
	}

	/** How many flushes actually had something to apply. */
	public long flushes() {
		return this.flushes.get();
	}

	/** How many flushes were refused because one was already running. */
	public long dropped() {
		return this.dropped.get();
	}

	/**
	 * How many events were refused because the subscription that delivered them had
	 * already been replaced. Expected to be zero on a screen whose watch never dies —
	 * which is why it is counted rather than assumed: a guard that never fires and a
	 * guard that is not there look identical from the outside.
	 */
	public long stale() {
		return this.stale.get();
	}

	/**
	 * How many events are buffered right now, waiting for a tick — including the ones
	 * staged behind a list that has not been installed yet, because "buffered" is a claim
	 * about what has been kept, not about what the next flush will apply.
	 */
	public int buffered() {
		this.lock.lock();
		try {
			return this.pending.size() + this.staged.size();
		}
		finally {
			this.lock.unlock();
		}
	}

	/**
	 * How many of {@link #buffered()} are being held until the current subscription's own
	 * list is installed. Counted rather than assumed, for the same reason
	 * {@link #stale()} is: a hold that never releases and a hold that is not there both
	 * look like a table that is simply quiet.
	 */
	public int held() {
		this.lock.lock();
		try {
			return this.staged.size();
		}
		finally {
			this.lock.unlock();
		}
	}

	/** What a flush did, and therefore whether the screen owes a repaint. */
	public enum Flush {

		/** Nothing was buffered. No repaint is owed and the tick cost a map check. */
		IDLE,

		/** The batch was applied to the model. A repaint is owed. */
		APPLIED,

		/** Another flush was running. Nothing was lost; the next tick applies it. */
		DROPPED;

		/** Whether the screen must repaint because of this flush. */
		public boolean repaints() {
			return this == APPLIED;
		}

	}

	/**
	 * One buffered change. The object is kept for the deleted case too, because its
	 * metadata is where the key comes from.
	 */
	private record Pending(boolean deleted, GenericKubernetesResource object) {
	}

}
