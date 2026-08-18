package org.alexmond.kweblens.tui.render;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import dev.tamboui.layout.Constraint;
import dev.tamboui.layout.Layout;
import dev.tamboui.layout.Rect;
import dev.tamboui.style.Style;
import dev.tamboui.terminal.Frame;
import dev.tamboui.tui.EventHandler;
import dev.tamboui.tui.Renderer;
import dev.tamboui.tui.TuiRunner;
import dev.tamboui.tui.event.Event;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.event.ResizeEvent;
import dev.tamboui.tui.event.TickEvent;
import dev.tamboui.widgets.paragraph.Paragraph;

import org.alexmond.kweblens.column.Column;
import org.alexmond.kweblens.tui.TuiPosture;
import org.alexmond.kweblens.tui.data.ResourceQuery;
import org.alexmond.kweblens.tui.detail.DetailModel;
import org.alexmond.kweblens.tui.screen.ColumnLayout;
import org.alexmond.kweblens.tui.screen.FrameTitle;
import org.alexmond.kweblens.tui.screen.KeyMap;
import org.alexmond.kweblens.tui.screen.Navigation;
import org.alexmond.kweblens.tui.screen.ResourceModel;
import org.alexmond.kweblens.tui.screen.ResourceRow;
import org.alexmond.kweblens.tui.screen.TickRate;
import org.alexmond.kweblens.tui.screen.View;
import org.alexmond.kweblens.tui.screen.ViewController;
import org.alexmond.kweblens.tui.screen.WatchCoalescer;
import org.alexmond.kweblens.tui.screen.WatchSupervisor;

/**
 * The live table: one repaint per tick, never one per event.
 *
 * <p>
 * This class is the whole of the coalescing rule's TUI half, and it is three lines of it:
 * a {@link TickEvent} asks {@link WatchCoalescer#flush()} whether the model changed and
 * repaints only if it did; a {@link KeyEvent} is translated and handed to
 * {@link ViewController}, which repaints only if something changed; nothing else repaints
 * at all. <b>Nothing here is posted from the watch thread</b> — see
 * {@link WatchCoalescer} for the measurement that makes that non-negotiable.
 *
 * <h2>Every key goes through the binding table</h2>
 *
 * A press becomes a {@code KeyStroke} ({@link Keys}) and then a {@code KeyAction}
 * ({@link KeyMap}). There is no {@code if (key.isChar('x'))} left in this file, and that
 * is the point: the hint bar and the help pane are projections of that same table, so a
 * key that does something the help does not mention is not a thing that can be written.
 *
 * <h2>And whether any of it is still true</h2>
 *
 * The same tick asks {@link WatchSupervisor}, which is the other half of an honest live
 * table: the coalescer says what changed, the supervisor says whether changes are still
 * arriving at all. A watch that has died puts a NOT LIVE notice at the front of the
 * header and repaints once a second while it is down, because a table that has quietly
 * stopped updating looks exactly like one that has nothing to report (GH#413).
 *
 * <h2>How a resize is noticed</h2>
 *
 * By comparing {@link Frame#area()} in {@link #render}, <b>not</b> by handling
 * {@link ResizeEvent}. TamboUI's {@code TuiRunner.run} consumes {@code ResizeEvent}
 * itself — {@code if (event instanceof ResizeEvent) { safeRender(...); continue; }} — so
 * a handler branch for it looks correct and never fires. The GH#361 spike measured 0
 * deliveries across three real {@code SIGWINCH}es while the layout redrew correctly each
 * time; {@link #resizeEventsSeenByHandler()} exists so a test can keep proving that,
 * because the two failure modes are indistinguishable if you only ever see the right
 * layout.
 *
 * <h2>v1 is read-only, and the header says so</h2>
 *
 * Every key here moves a cursor, opens a prompt or navigates. There is no key that
 * changes the cluster, because {@code ClusterDataSource} has no method that could.
 */
public class ResourceScreen implements EventHandler, Renderer {

	/** How much of a screen ctrl-d and ctrl-u move. */
	private static final int HALF_PAGE_DIVISOR = 2;

	/** Header, frame title, hint bar, status line: the rows the table does not get. */
	private static final int CHROME_LINES = 4;

	private final ResourceModel model;

	private final WatchCoalescer coalescer;

	private final WatchSupervisor supervisor;

	private final TickRate tick;

	private final ViewController controller;

	private final ResourceTableView table = new ResourceTableView();

	private final DetailPaneView pane = new DetailPaneView();

	private final LogPaneView logs = new LogPaneView();

	private final Style chrome = Style.create().dim();

	private final Style titleStyle = Style.create().bold();

	private final AtomicInteger renders = new AtomicInteger();

	private final AtomicInteger layouts = new AtomicInteger();

	private final AtomicInteger resizeEvents = new AtomicInteger();

	private final AtomicInteger rowsBuilt = new AtomicInteger();

	private final AtomicInteger ticks = new AtomicInteger();

	/**
	 * The kind and scope on screen; replaced by {@link #retarget} when a view changes.
	 */
	private ResourceQuery query;

	/** The area the current layout was computed for. Null until the first frame. */
	private Rect area;

	private ColumnLayout columns = ColumnLayout.forWidth(0);

	/** The current kind's own column headings — empty for a kind that declares none. */
	private List<String> headers = List.of();

	/**
	 * A screen with nowhere to navigate: it draws, it moves its cursor, and every command
	 * says the cluster serves no kinds. What the chrome tests use.
	 */
	public ResourceScreen(ResourceModel model, WatchCoalescer coalescer, WatchSupervisor supervisor,
			ResourceQuery query, TickRate tick) {
		this(model, coalescer, supervisor, query, tick, new NoNavigation(query));
	}

	public ResourceScreen(ResourceModel model, WatchCoalescer coalescer, WatchSupervisor supervisor,
			ResourceQuery query, TickRate tick, Navigation navigation) {
		this.model = model;
		this.coalescer = coalescer;
		this.supervisor = supervisor;
		this.query = query;
		this.tick = tick;
		this.controller = new ViewController(navigation, model, View.of(query.descriptor(), query.namespace()),
				this::halfPage);
	}

	@Override
	public boolean handle(Event event, TuiRunner runner) {
		if (event instanceof TickEvent) {
			// The supervisor first: a recovery replaces the model wholesale, and anything
			// the new watch buffered while it was re-listing belongs on top of that, not
			// under it. It is also the half that owes a repaint when nothing else does —
			// a frozen table has to change on screen or it is still a lie.
			boolean repaint = this.supervisor.tick();
			repaint |= this.coalescer.flush().repaints();
			// The log pane's flush, on the same period and for the same reason (GH#369).
			// A second timer would be a second answer to "when may the terminal repaint",
			// and the tick is the one the keyboard budget on TickRate was computed
			// against.
			repaint |= this.controller.flushLogs();
			// Counted LAST, and GH#423 is what that sentence costs when it is not.
			// ScreenHarness.tickAndSettle uses this counter as its barrier, so a counter
			// bumped on ENTRY means "a tick started" while claiming to mean "a tick
			// finished": the test was released mid-tick, moved the clock sixty seconds,
			// and the supervisor call still in flight then read the new time and started
			// a
			// retry the test had already decided was not due. Green on this box, red on a
			// slower one, for three commits.
			this.ticks.incrementAndGet();
			return repaint;
		}
		if (event instanceof ResizeEvent) {
			// Unreachable in TuiRunner.run, and counted rather than assumed — see the
			// class javadoc. If this ever climbs, TamboUI changed and the renderer's
			// area check is no longer the only way a resize is observed.
			this.resizeEvents.incrementAndGet();
			return true;
		}
		if (event instanceof KeyEvent key) {
			return key(key, runner);
		}
		return false;
	}

	private boolean key(KeyEvent key, TuiRunner runner) {
		ViewController.Outcome outcome = this.controller.key(Keys.of(key));
		if (outcome == ViewController.Outcome.QUIT) {
			runner.quit();
			return false;
		}
		return outcome == ViewController.Outcome.REPAINT;
	}

	/**
	 * Point the screen at another kind. Called by {@link ScreenSession#switchTo} on the
	 * render thread; the layout is invalidated because a cluster-scoped kind has no
	 * NAMESPACE column and a namespaced one does.
	 */
	void retarget(ResourceQuery next) {
		retarget(next, List.of());
	}

	/**
	 * Point the screen at another kind <em>and</em> at that kind's own columns (GH#367).
	 *
	 * <p>
	 * The two arrive together because they are one fact. A layout kept from the previous
	 * kind would put the last kind's headings over this kind's cells, which is the same
	 * defect as drawing the last kind's rows under this kind's title — invalidating the
	 * area is what forces both to be recomputed on the next frame.
	 * @param next the kind and scope
	 * @param columns that kind's columns, in order
	 */
	void retarget(ResourceQuery next, List<Column> columns) {
		this.query = next;
		this.headers = columns.stream().map(Column::header).toList();
		this.table.headers(this.headers);
		this.area = null;
	}

	@Override
	public void render(Frame frame) {
		Rect full = frame.area();
		boolean relayout = !full.equals(this.area);
		if (relayout) {
			this.area = full;
			this.columns = ColumnLayout.forWidth(Math.max(0, full.width() - ResourceTableView.CURSOR.length()),
					this.query.descriptor().namespaced(), this.headers);
		}
		var parts = Layout.vertical()
			.constraints(Constraint.length(1), Constraint.length(1), Constraint.fill(1), Constraint.length(1),
					Constraint.length(1))
			.split(full);
		frame.renderWidget(Paragraph.builder().text(header()).style(this.chrome).build(), parts.get(0));
		frame.renderWidget(Paragraph.builder().text(title()).style(this.titleStyle).build(), parts.get(1));
		int built = body(frame, parts.get(2));
		frame.renderWidget(Paragraph.builder().text(hints()).style(this.chrome).build(), parts.get(3));
		frame.renderWidget(Paragraph.builder().text(footer()).style(this.chrome).build(), parts.get(4));
		// Counters last, and renders last of all: an observer that waits on one of these
		// must not be able to read a half-drawn frame's numbers. That is not tidiness —
		// awaiting on a counter written first is how a test reads the PREVIOUS frame's
		// row count and concludes the window never changed.
		this.rowsBuilt.set(built);
		if (relayout) {
			this.layouts.incrementAndGet();
		}
		this.renders.incrementAndGet();
	}

	/**
	 * The hint bar is the projection of whichever table owns the keyboard right now. A
	 * bar naming {@code :} while a pane has the keys would be the exact failure
	 * {@link KeyMap} exists to prevent, arrived at from the other side.
	 */
	String hints() {
		if (this.controller.logsOpen()) {
			return KeyMap.logHints();
		}
		return (this.controller.paneOpen()) ? KeyMap.paneHints() : KeyMap.hints();
	}

	/** The table, or one of the two panes, or the help pane over whichever it is. */
	private int body(Frame frame, Rect area) {
		if (this.controller.help()) {
			// The help is a document with a cursor and it is longer than a terminal
			// (#470), so it is drawn by the same windowed view as the detail pane rather
			// than as one paragraph the bottom of which nobody could reach.
			return this.pane.render(frame, area, this.controller.helpDocument());
		}
		if (this.controller.logsOpen()) {
			return this.logs.render(frame, area, this.controller.logs());
		}
		if (this.controller.paneOpen()) {
			return this.pane.render(frame, area, this.controller.detail());
		}
		return this.table.render(frame, area, this.model, this.columns);
	}

	/**
	 * The chrome line, and the one place a dead watch is admitted to.
	 *
	 * <p>
	 * The notice goes <b>in front</b> of everything else rather than after it. Every
	 * field that follows — the row count above all — is a claim about the cluster, and
	 * once the watch has stopped they are claims about a moment that has passed; a
	 * warning appended at the end is also the first thing a narrow terminal drops.
	 */
	String header() {
		String scope = scope();
		StringBuilder line = new StringBuilder(192);
		String notice = this.supervisor.notice();
		if (!notice.isEmpty()) {
			line.append(notice).append("  │  ");
		}
		line.append("kweblens-tui ")
			.append(TuiPosture.current().badge())
			.append(" · ")
			.append(this.query.clusterId())
			.append(" · ")
			.append(this.query.kind())
			.append(" · ")
			.append(scope)
			.append(" · ")
			.append(this.model.size())
			.append(" rows · tick ")
			.append(this.tick.millis())
			.append("ms");
		if (this.tick.clamped()) {
			line.append(" (").append(this.tick.notice()).append(')');
		}
		return line.toString();
	}

	/**
	 * The frame title and the breadcrumbs: what you are looking at, and every level you
	 * can walk back through.
	 */
	String title() {
		if (this.controller.logsOpen()) {
			// The log pane's own title, and it carries "previous run (snapshot)" when
			// that
			// is what is on screen — a reader who takes a terminated instance's log for
			// the live one draws exactly the wrong conclusion about a crashloop.
			return this.controller.logs().title();
		}
		if (this.controller.paneOpen()) {
			// "snapshot" is not decoration. The table under the pane goes on updating
			// from
			// the watch and the pane does not — its YAML, relations and events were read
			// together to describe one moment, and refreshing one of them would put two
			// moments on one screen. Closing and reopening is the refresh.
			return "detail (snapshot)   │   " + this.controller.detail().subject();
		}
		StringBuilder line = new StringBuilder(96);
		line.append(FrameTitle.of(this.controller.current(), this.model.size()));
		if (this.model.size() != this.model.total()) {
			line.append(" of ").append(this.model.total());
		}
		List<String> crumbs = this.controller.crumbs();
		if (crumbs.size() > 1) {
			line.append("   │   ");
			for (String crumb : crumbs) {
				line.append('<').append(crumb).append(">   ");
			}
		}
		return line.toString().stripTrailing();
	}

	/**
	 * The bottom line: the prompt when one is open, otherwise where the cursor is and
	 * anything that could not be done.
	 */
	String footer() {
		if (this.controller.prompt().open()) {
			return this.controller.prompt().rendered() + candidates();
		}
		if (this.controller.logsOpen()) {
			return logFooter();
		}
		if (this.controller.paneOpen()) {
			return paneFooter();
		}
		String selected = this.model.selectedRow().map(ResourceRow::name).orElse("—");
		int position = (this.model.size() == 0) ? 0 : this.model.selectedIndex() + 1;
		String line = position + "/" + this.model.size() + "  " + selected;
		String message = this.controller.message();
		return (message.isEmpty()) ? line : line + "   │   " + message;
	}

	/**
	 * Where the cursor is in the pane, what the search found, and anything that could not
	 * be done. The search status is in words — "no match" is a result, and a screen that
	 * did not move after {@code /} is indistinguishable from a search that never ran.
	 */
	private String paneFooter() {
		DetailModel detail = this.controller.detail();
		String line = (detail.selectedIndex() + 1) + "/" + detail.size();
		String search = detail.searchStatus();
		if (!search.isEmpty()) {
			line = line + "   │   " + search;
		}
		String message = this.controller.message();
		return (message.isEmpty()) ? line : line + "   │   " + message;
	}

	/**
	 * Where the cursor is in the log, whether lines are still arriving, and anything that
	 * could not be done. {@code LogModel.status()} leads with a stream that has stopped,
	 * for the reason the header leads with NOT LIVE (GH#413): every number after it is a
	 * claim about a moment that has passed.
	 */
	private String logFooter() {
		String line = this.controller.logs().status();
		String message = this.controller.message();
		return (message.isEmpty()) ? line : line + "   │   " + message;
	}

	private String candidates() {
		List<String> candidates = this.controller.prompt().candidates();
		return (candidates.isEmpty()) ? "" : "   │   " + String.join("  ", candidates);
	}

	/**
	 * What the list is scoped to. A cluster-scoped kind is <b>not</b> "all namespaces" —
	 * that phrase claims the list was narrowed and was not, and a Node has no namespace
	 * to narrow by.
	 */
	private String scope() {
		if (!this.query.descriptor().namespaced()) {
			return "cluster-scoped";
		}
		return (this.query.namespace() == null || this.query.namespace().isBlank()) ? "all namespaces"
				: this.query.namespace();
	}

	private int halfPage() {
		return Math.max(1, viewportHeight() / HALF_PAGE_DIVISOR);
	}

	private int viewportHeight() {
		return (this.area != null) ? Math.max(1, this.area.height() - CHROME_LINES) : 1;
	}

	/** The view stack, the prompt and the history — what the keys act on. */
	public ViewController controller() {
		return this.controller;
	}

	/** How many times {@link #render} ran — the TUI's definition of a repaint. */
	public int renders() {
		return this.renders.get();
	}

	/**
	 * How many ticks this handler has <b>finished</b>. A tick is not a repaint — that is
	 * the whole point — so a test that wants to know a tick landed must read this and not
	 * {@link #renders()}.
	 *
	 * <p>
	 * <b>Finished, not started</b>, and the distinction is load-bearing: this counter is
	 * what {@code ScreenHarness.tickAndSettle} waits on, so a test that reads state after
	 * it must be able to rely on no tick being in flight. See {@link #handle} (GH#423).
	 */
	public int ticksHandled() {
		return this.ticks.get();
	}

	/** How many times the column widths were recomputed, i.e. how many re-layouts. */
	public int layouts() {
		return this.layouts.get();
	}

	/**
	 * How many {@link ResizeEvent}s reached this handler. Expected to stay at zero: the
	 * runner consumes them. A test asserts that, because a handler that never fires looks
	 * exactly like one that works when the layout is right either way.
	 */
	public int resizeEventsSeenByHandler() {
		return this.resizeEvents.get();
	}

	/** Widget rows built by the last frame — bounded by the viewport, not the model. */
	public int rowsBuiltLastFrame() {
		return this.rowsBuilt.get();
	}

	/** The area the last frame was laid out for; null before the first frame. */
	public Rect observedArea() {
		return this.area;
	}

	/** The column widths in force. */
	public ColumnLayout columns() {
		return this.columns;
	}

}
