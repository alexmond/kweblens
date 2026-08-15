package org.alexmond.kweblens.tui.render;

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

import org.alexmond.kweblens.tui.TuiPosture;
import org.alexmond.kweblens.tui.data.ResourceQuery;
import org.alexmond.kweblens.tui.screen.ColumnLayout;
import org.alexmond.kweblens.tui.screen.ResourceModel;
import org.alexmond.kweblens.tui.screen.ResourceRow;
import org.alexmond.kweblens.tui.screen.TickRate;
import org.alexmond.kweblens.tui.screen.WatchCoalescer;

/**
 * The live table: one repaint per tick, never one per event.
 *
 * <p>
 * This class is the whole of the coalescing rule's TUI half, and it is three lines of it:
 * a {@link TickEvent} asks {@link WatchCoalescer#flush()} whether the model changed and
 * repaints only if it did; a {@link KeyEvent} moves the cursor and repaints only if the
 * cursor moved; nothing else repaints at all. <b>Nothing here is posted from the watch
 * thread</b> — see {@link WatchCoalescer} for the measurement that makes that
 * non-negotiable.
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
 * Every key here moves a cursor or quits. There is no key that changes the cluster,
 * because {@code ClusterDataSource} has no method that could.
 */
public class ResourceScreen implements EventHandler, Renderer {

	/** How much of a screen ctrl-d and ctrl-u move. */
	private static final int HALF_PAGE_DIVISOR = 2;

	private final ResourceModel model;

	private final WatchCoalescer coalescer;

	private final ResourceQuery query;

	private final TickRate tick;

	private final ResourceTableView table = new ResourceTableView();

	private final Style chrome = Style.create().dim();

	private final AtomicInteger renders = new AtomicInteger();

	private final AtomicInteger layouts = new AtomicInteger();

	private final AtomicInteger resizeEvents = new AtomicInteger();

	private final AtomicInteger rowsBuilt = new AtomicInteger();

	private final AtomicInteger ticks = new AtomicInteger();

	/** The area the current layout was computed for. Null until the first frame. */
	private Rect area;

	private ColumnLayout columns = ColumnLayout.forWidth(0);

	public ResourceScreen(ResourceModel model, WatchCoalescer coalescer, ResourceQuery query, TickRate tick) {
		this.model = model;
		this.coalescer = coalescer;
		this.query = query;
		this.tick = tick;
	}

	@Override
	public boolean handle(Event event, TuiRunner runner) {
		if (event instanceof TickEvent) {
			this.ticks.incrementAndGet();
			return this.coalescer.flush().repaints();
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
		if (key.isQuit() || key.isCharIgnoreCase('q') || key.isCtrlC()) {
			runner.quit();
			return false;
		}
		int page = Math.max(1, viewportHeight() / HALF_PAGE_DIVISOR);
		if (key.isDown() || key.isChar('j')) {
			return this.model.moveSelection(1);
		}
		if (key.isUp() || key.isChar('k')) {
			return this.model.moveSelection(-1);
		}
		if (key.isPageDown() || (key.isChar('d') && key.hasCtrl())) {
			return this.model.moveSelection(page);
		}
		if (key.isPageUp() || (key.isChar('u') && key.hasCtrl())) {
			return this.model.moveSelection(-page);
		}
		if (key.isHome() || key.isChar('g')) {
			return this.model.selectTo(0);
		}
		if (key.isEnd() || key.isChar('G')) {
			return this.model.selectTo(Integer.MAX_VALUE);
		}
		return false;
	}

	@Override
	public void render(Frame frame) {
		Rect full = frame.area();
		boolean relayout = !full.equals(this.area);
		if (relayout) {
			this.area = full;
			this.columns = ColumnLayout.forWidth(Math.max(0, full.width() - ResourceTableView.CURSOR.length()),
					this.query.descriptor().namespaced());
		}
		var parts = Layout.vertical()
			.constraints(Constraint.length(1), Constraint.fill(1), Constraint.length(1))
			.split(full);
		frame.renderWidget(Paragraph.builder().text(header()).style(this.chrome).build(), parts.get(0));
		int built = this.table.render(frame, parts.get(1), this.model, this.columns);
		frame.renderWidget(Paragraph.builder().text(footer()).style(this.chrome).build(), parts.get(2));
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

	String header() {
		String scope = scope();
		StringBuilder line = new StringBuilder(128);
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

	String footer() {
		String selected = this.model.selectedRow().map(ResourceRow::name).orElse("—");
		int position = (this.model.size() == 0) ? 0 : this.model.selectedIndex() + 1;
		return "j/k move · ctrl-d/u page · g/G ends · q quit   │   " + position + "/" + this.model.size() + "  "
				+ selected;
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

	private int viewportHeight() {
		return (this.area != null) ? Math.max(1, this.area.height() - 3) : 1;
	}

	/** How many times {@link #render} ran — the TUI's definition of a repaint. */
	public int renders() {
		return this.renders.get();
	}

	/**
	 * How many ticks this handler has processed. A tick is not a repaint — that is the
	 * whole point — so a test that wants to know a tick landed must read this and not
	 * {@link #renders()}.
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
