package org.alexmond.kweblens.tui.screen;

import java.util.List;
import java.util.Optional;
import java.util.function.IntSupplier;

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;

import org.alexmond.kweblens.resource.ResourceDescriptor;
import org.alexmond.kweblens.tui.data.ObjectDetail;
import org.alexmond.kweblens.tui.data.ResourceQuery;
import org.alexmond.kweblens.tui.detail.DetailModel;
import org.alexmond.kweblens.tui.detail.DetailSections;

/**
 * Every key that is not a cursor move: the command line, the filter, the view stack, the
 * history and the number keys — <b>and none of TamboUI</b>.
 *
 * <p>
 * The renderer's whole job with a key press is to turn it into a {@link KeyStroke} and
 * hand it here. That is deliberate and it is what makes the rest of this ticket
 * assertable: {@code esc} clearing a filter before it pops, {@code [} walking history
 * without touching the stack, {@code :po} resolving through discovery — every one of them
 * is a method call on this class in a test with no terminal, no cluster and no frame.
 *
 * <h2>The three navigations, kept apart on purpose</h2>
 *
 * <ul>
 * <li><b>The stack</b> ({@link ViewStack}) is where you are. {@code :} pushes, drilling
 * in pushes, {@code esc} pops.</li>
 * <li><b>The history</b> ({@link CommandHistory}) is what you have asked for. {@code [},
 * {@code ]} and {@code -} walk it and <em>re-run</em> commands, which pushes stack levels
 * — but walking it never pops one, and pushing one never records a command.</li>
 * <li><b>The favourites</b> ({@link NamespaceFavourites}) are where you have been. The
 * number keys re-scope the level you are on rather than pushing a new one, because
 * {@code 1} is "same list, other namespace".</li>
 * </ul>
 *
 * <h2>What it will not do</h2>
 *
 * Change anything. Every key here opens, narrows or navigates; {@code ClusterDataSource}
 * has no write method for one to call even if somebody added a binding for it.
 */
public class ViewController {

	private final Navigation navigation;

	private final ResourceModel model;

	private final ViewStack stack;

	private final CommandHistory history = new CommandHistory();

	private final NamespaceFavourites favourites = new NamespaceFavourites();

	private final CommandLineModel prompt;

	/** How far {@code ctrl-d} moves — the renderer's viewport, which only it knows. */
	private final IntSupplier pageSize;

	/** The last thing that could not be done, and why. Empty when nothing is wrong. */
	private String message = "";

	private boolean help;

	/**
	 * The detail pane, or null when it is closed.
	 *
	 * <p>
	 * <b>It is a snapshot and it says so in the title.</b> The table under it goes on
	 * updating from the watch; the pane does not, because its three parts were read
	 * together to describe one moment (see {@code ObjectDetail}) and refreshing one of
	 * them would put two moments on one screen. Closing and reopening is the refresh.
	 */
	private DetailModel detail;

	public ViewController(Navigation navigation, ResourceModel model, View root, IntSupplier pageSize) {
		this.navigation = navigation;
		this.model = model;
		this.stack = new ViewStack(root);
		this.pageSize = pageSize;
		this.prompt = new CommandLineModel((prefix, limit) -> navigation.kinds().complete(prefix, limit));
		this.favourites.remember(root.namespace());
	}

	/**
	 * One key press.
	 * @return what the caller owes: nothing, a repaint, or a quit
	 */
	public Outcome key(KeyStroke stroke) {
		if (stroke == null) {
			return Outcome.NONE;
		}
		if (this.prompt.open()) {
			return promptKey(stroke);
		}
		if (this.help) {
			// Any key at all closes the help pane. A help screen you have to find the
			// right key to leave is a help screen that needs help.
			this.help = false;
			return Outcome.REPAINT;
		}
		if (this.detail != null) {
			return paneKey(stroke);
		}
		return KeyMap.action(stroke).map((action) -> act(action, stroke)).orElse(Outcome.NONE);
	}

	/**
	 * One key press while the detail pane is up.
	 *
	 * <p>
	 * The pane has its own binding table ({@link KeyMap#PANE_BINDINGS}) because it is a
	 * document and not a list: {@code /} finds a line rather than narrowing rows, and
	 * {@code :} would be a command line over a table nobody is looking at. The derivation
	 * rule is unchanged — the pane's hint bar is that table's projection, and
	 * {@code KeyMapTest} checks it in both directions like the other one.
	 */
	private Outcome paneKey(KeyStroke stroke) {
		this.message = "";
		return KeyMap.paneAction(stroke).map(this::paneAct).orElse(Outcome.NONE);
	}

	private Outcome paneAct(KeyAction action) {
		return switch (action) {
			case MOVE_DOWN -> repaintIf(this.detail.moveSelection(1));
			case MOVE_UP -> repaintIf(this.detail.moveSelection(-1));
			case PAGE_DOWN -> repaintIf(this.detail.moveSelection(page()));
			case PAGE_UP -> repaintIf(this.detail.moveSelection(-page()));
			case TOP -> repaintIf(this.detail.selectTo(0));
			case BOTTOM -> repaintIf(this.detail.selectTo(Integer.MAX_VALUE));
			case SEARCH -> repaintIf(this.prompt.open(CommandLineModel.Mode.SEARCH, this.detail.query()));
			case NEXT_MATCH -> match(true);
			case PREVIOUS_MATCH -> match(false);
			case BACK -> closePane();
			case QUIT -> Outcome.QUIT;
			default -> Outcome.NONE;
		};
	}

	/**
	 * {@code esc} clears the search before it closes the pane — the same order
	 * {@link ViewStack#back()} keeps for a filter and a level, for the same reason: one
	 * press must undo one thing, or the operator has to guess which of the two it undid.
	 */
	private Outcome closePane() {
		if (this.detail.clearSearch()) {
			return Outcome.REPAINT;
		}
		this.detail = null;
		return Outcome.REPAINT;
	}

	private Outcome match(boolean forwards) {
		if (this.detail.matchCount() == 0) {
			this.message = (this.detail.query().isEmpty()) ? "Press / to search this pane first."
					: "No line matches '" + this.detail.query() + "'.";
			return Outcome.REPAINT;
		}
		return repaintIf((forwards) ? this.detail.nextMatch() : this.detail.previousMatch());
	}

	/**
	 * Open the pane on the selected row — YAML, the server's relations and the object's
	 * events (GH#368).
	 *
	 * <p>
	 * <b>The headline is the verdict the list already computed</b>, taken off the row
	 * rather than asked for again: {@code ObjectStates.forList} opens one status context
	 * per page, and a per-object verdict on the way in would open one per object opened.
	 *
	 * <p>
	 * A read the cluster refuses lands in {@link #message} and the pane does not open.
	 * That is the same shape as a navigation that could not be filled — there is nothing
	 * to draw, and an empty pane would be a claim that this object has no relations and
	 * no events.
	 */
	private Outcome openDetail() {
		Optional<ResourceRow> row = this.model.selectedRow();
		if (row.isEmpty()) {
			this.message = "Nothing selected.";
			return Outcome.REPAINT;
		}
		ResourceRow selected = row.get();
		ObjectDetail read = this.navigation.detail(selected.namespace(), selected.name());
		if (!read.available()) {
			this.message = read.error();
			return Outcome.REPAINT;
		}
		String kind = current().descriptor().kind();
		String headline = DetailSections.headline(selected.state(), kind, selected.namespace(), selected.name());
		this.detail = new DetailModel(headline, DetailSections.of(read));
		return Outcome.REPAINT;
	}

	private Outcome act(KeyAction action, KeyStroke stroke) {
		this.message = "";
		return switch (action) {
			case MOVE_DOWN -> repaintIf(this.model.moveSelection(1));
			case MOVE_UP -> repaintIf(this.model.moveSelection(-1));
			case PAGE_DOWN -> repaintIf(this.model.moveSelection(page()));
			case PAGE_UP -> repaintIf(this.model.moveSelection(-page()));
			case TOP -> repaintIf(this.model.selectTo(0));
			case BOTTOM -> repaintIf(this.model.selectTo(Integer.MAX_VALUE));
			case COMMAND -> repaintIf(this.prompt.open(CommandLineModel.Mode.COMMAND, ""));
			case FILTER -> repaintIf(this.prompt.open(CommandLineModel.Mode.FILTER, current().filter()));
			case DRILL_IN -> drillIn();
			case BACK -> back(quitsAtRoot(stroke));
			case QUIT -> Outcome.QUIT;
			case HISTORY_PREVIOUS -> replay(this.history.previous());
			case HISTORY_NEXT -> replay(this.history.next());
			case LAST_COMMAND -> replay(this.history.last());
			case NAMESPACE_FAVOURITE -> namespace(stroke.character());
			case HELP -> toggleHelp();
			case DETAIL -> openDetail();
			// Pane-only actions. Listed so this switch stays exhaustive — adding a
			// KeyAction must force a decision here — and unreachable because no row of
			// KeyMap.BINDINGS produces one.
			case SEARCH, NEXT_MATCH, PREVIOUS_MATCH -> Outcome.NONE;
		};
	}

	/**
	 * {@code esc} and {@code q} are the same handler and differ in one thing: at the root
	 * of the stack with nothing to clear, {@code q} means "I am done" and {@code esc}
	 * means nothing at all. Making {@code esc} quit would put the exit one keystroke away
	 * from a reflex.
	 */
	private static boolean quitsAtRoot(KeyStroke stroke) {
		return stroke.kind() == KeyStroke.Kind.CHARACTER;
	}

	private Outcome back(boolean quitAtRoot) {
		return switch (this.stack.back()) {
			case FILTER_CLEARED -> {
				this.model.applyFilter(RowFilters.ALL);
				yield Outcome.REPAINT;
			}
			case POPPED -> {
				open(current());
				yield Outcome.REPAINT;
			}
			case AT_ROOT -> (quitAtRoot) ? Outcome.QUIT : Outcome.NONE;
		};
	}

	private Outcome promptKey(KeyStroke stroke) {
		return switch (stroke.kind()) {
			case ESCAPE -> {
				this.prompt.close();
				yield Outcome.REPAINT;
			}
			case ENTER -> submit();
			case BACKSPACE -> repaintIf(this.prompt.backspace());
			case TAB -> repaintIf(this.prompt.complete());
			default -> repaintIf(stroke.printable() && this.prompt.type(stroke.character()));
		};
	}

	/**
	 * Enter on an open prompt.
	 *
	 * <p>
	 * <b>Every branch repaints, because every branch has already closed the prompt</b>
	 * (GH#461) — the same reason {@link #closePane()}, {@link #back()} and the
	 * {@code ESCAPE} arm of {@link #promptKey} return {@link Outcome#REPAINT} without
	 * asking whether anything else moved. Closing it <em>is</em> the change: the frame on
	 * screen still has the prompt drawn in its footer, and nothing else will take it
	 * down. TamboUI skips {@code safeRender} entirely when the handler returns false, and
	 * over a quiet cluster with a live watch neither {@code WatchSupervisor.tick} nor the
	 * coalescer's flush owes a frame either. An operator looking at a prompt the model
	 * has closed types their next character into the pane, where {@code q} is not a
	 * character — it is BACK, and it closes what they were reading.
	 *
	 * <p>
	 * So the question this method must not ask is "did the search find anything": that is
	 * an answer about the document, and what changed is the prompt. {@code SEARCH} was
	 * the only mode that ever asked it, which is why it was the only one that could
	 * report NONE.
	 */
	private Outcome submit() {
		String typed = this.prompt.text();
		CommandLineModel.Mode mode = this.prompt.mode();
		this.prompt.close();
		return switch (mode) {
			case SEARCH -> search(typed);
			case FILTER -> filter(typed);
			case COMMAND -> command(typed);
			// Unreachable: the prompt was open or this method was not called. Listed so
			// the switch stays exhaustive and a new mode has to decide here.
			case OFF -> Outcome.REPAINT;
		};
	}

	/**
	 * Find a line in the pane. A blank term clears the search, an unmatched one is
	 * reported in words by {@link DetailModel#searchStatus()}, and a search submitted
	 * with the pane already gone does nothing to a document that is not there — none of
	 * which changes what the caller owes, because the prompt is down either way.
	 */
	private Outcome search(String typed) {
		if (this.detail != null) {
			this.detail.search(typed);
		}
		return Outcome.REPAINT;
	}

	/** Run a {@code :} line, and remember it only if it went somewhere. */
	private Outcome command(String typed) {
		Outcome outcome = run(typed);
		if (this.message.isEmpty()) {
			this.history.record(typed);
		}
		return outcome;
	}

	/**
	 * Narrow the level being shown. <b>No re-list:</b> the filter narrows the view, not
	 * the collection, so every row the cluster sent is still in the model and clearing
	 * the filter shows them again without asking the API server twice.
	 */
	private Outcome filter(String query) {
		this.stack.filter(query);
		View view = current();
		this.model.applyFilter(RowFilters.of(view.filter(), view.descriptor().kind()));
		return Outcome.REPAINT;
	}

	/** Run a {@code :} line, pushing a level for it. */
	public Outcome run(String line) {
		this.message = "";
		CommandRequest request = CommandRequest.parse(line);
		if (request.failed()) {
			this.message = request.error();
			return Outcome.REPAINT;
		}
		Optional<ResourceDescriptor> kind = this.navigation.kinds().resolve(request.kind());
		if (kind.isEmpty()) {
			this.message = unknown(request.kind());
			return Outcome.REPAINT;
		}
		push(View.of(kind.get(), request.namespace(), request.filter()));
		return Outcome.REPAINT;
	}

	private String unknown(String token) {
		int known = this.navigation.kinds().size();
		if (known == 0) {
			return "No kinds discovered on this cluster, so ':" + token + "' cannot be resolved. "
					+ "Discovery failed or was refused — check the log.";
		}
		List<String> near = this.navigation.kinds().complete(token, 3);
		String hint = (near.isEmpty()) ? "" : " Did you mean: " + String.join(", ", near) + "?";
		return "No kind named '" + token + "' among the " + known + " this cluster serves." + hint;
	}

	private Outcome replay(Optional<String> command) {
		if (command.isEmpty()) {
			this.message = "Nothing there in the command history.";
			return Outcome.REPAINT;
		}
		// Deliberately not recorded: walking history must not rewrite the thing being
		// walked, or [ [ ] lands somewhere that depends on what you did three commands
		// ago. CommandHistory.last() is the one that records, because toggling is its
		// job.
		return run(command.get());
	}

	private Outcome namespace(char digit) {
		if (!current().descriptor().namespaced()) {
			this.message = current().crumb() + " is cluster-scoped — there is no namespace to jump to.";
			return Outcome.REPAINT;
		}
		if (this.favourites.isAll(digit)) {
			this.stack.replace(View.of(current().descriptor(), null, current().filter()));
			open(current());
			return Outcome.REPAINT;
		}
		Optional<String> namespace = this.favourites.at(digit);
		if (namespace.isEmpty()) {
			this.message = "No namespace on " + digit + " yet — the digits fill up as you visit namespaces.";
			return Outcome.REPAINT;
		}
		this.stack.replace(View.of(current().descriptor(), namespace.get(), current().filter()));
		open(current());
		return Outcome.REPAINT;
	}

	/**
	 * Enter the selected row — and the relationship arrives as a query in the title, not
	 * as a join nobody can see. See {@link DrillDown}.
	 */
	private Outcome drillIn() {
		Optional<ResourceRow> row = this.model.selectedRow();
		if (row.isEmpty()) {
			this.message = "Nothing selected.";
			return Outcome.REPAINT;
		}
		GenericKubernetesResource object = this.navigation.object(row.get().namespace(), row.get().name());
		DrillDown.Target target = DrillDown.from(current().descriptor().kind(), object);
		if (!target.available()) {
			// The decline stays DrillDown's own words — it is the class that knows why
			// the
			// grammar cannot express the relationship. What is added is the other door:
			// for
			// most kinds there is nowhere to drill to and the detail pane is what the
			// operator wanted.
			this.message = target.reason() + " Press d for this object's detail.";
			return Outcome.REPAINT;
		}
		Optional<ResourceDescriptor> kind = this.navigation.kinds().resolve(target.kind());
		if (kind.isEmpty()) {
			this.message = "This cluster serves no '" + target.kind() + "', so there is nowhere to go.";
			return Outcome.REPAINT;
		}
		push(View.of(kind.get(), target.namespace(), target.filter()));
		return Outcome.REPAINT;
	}

	private Outcome toggleHelp() {
		this.help = !this.help;
		return Outcome.REPAINT;
	}

	private void push(View view) {
		this.stack.push(view);
		open(view);
	}

	/**
	 * Ask the session for this view's rows, and apply its filter to them.
	 *
	 * <p>
	 * <b>A navigation that could not be filled says so here</b> (GH#434). The session
	 * subscribes and lists on this thread, so a cluster that refuses either one has no
	 * other way to reach the operator: it hands back a sentence, and it lands in the same
	 * {@link #message} as "this cluster serves no such kind" one case over. The level
	 * stays where the operator put it and the table is empty — see
	 * {@code ScreenSession.switchTo} for why an empty view the header calls NOT LIVE is
	 * the coherent state and a rollback is not.
	 */
	private void open(View view) {
		this.favourites.remember(view.namespace());
		this.message = this.navigation.show(
				new ResourceQuery(this.navigation.clusterId(), view.descriptor(), view.namespace()),
				RowFilters.of(view.filter(), view.descriptor().kind()));
	}

	private int page() {
		return Math.max(1, this.pageSize.getAsInt());
	}

	private static Outcome repaintIf(boolean changed) {
		return (changed) ? Outcome.REPAINT : Outcome.NONE;
	}

	/** The level being shown. */
	public View current() {
		return this.stack.current();
	}

	/** The breadcrumbs, outermost first — one per level. */
	public List<String> crumbs() {
		return this.stack.crumbs();
	}

	/** How deep the stack is. */
	public int depth() {
		return this.stack.depth();
	}

	/** The prompt, so the renderer can draw it. */
	public CommandLineModel prompt() {
		return this.prompt;
	}

	/** The command history — exposed for the test that proves it is not the stack. */
	public CommandHistory history() {
		return this.history;
	}

	/** The number keys' namespaces. */
	public NamespaceFavourites favourites() {
		return this.favourites;
	}

	/**
	 * How many kinds the command line can reach. Zero is a real and reportable state:
	 * discovery failed, and the help pane says so rather than showing an empty prompt.
	 */
	public int kindCount() {
		return this.navigation.kinds().size();
	}

	/** What could not be done, and why. Empty when nothing is wrong. */
	public String message() {
		return this.message;
	}

	/** Whether the help pane is up. */
	public boolean help() {
		return this.help;
	}

	/** Whether the detail pane is up. */
	public boolean paneOpen() {
		return this.detail != null;
	}

	/**
	 * The detail pane's document, or null when it is closed — ask {@link #paneOpen()}
	 * first.
	 */
	public DetailModel detail() {
		return this.detail;
	}

	/** What a key press left the caller owing. */
	public enum Outcome {

		/** Nothing changed; do not repaint. */
		NONE,

		/** Something changed and the screen owes a frame. */
		REPAINT,

		/** The operator is finished. */
		QUIT

	}

}
