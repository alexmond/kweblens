package org.alexmond.kweblens.tui.screen;

import java.util.List;
import java.util.Optional;
import java.util.function.IntSupplier;

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;

import org.alexmond.kweblens.resource.ResourceDescriptor;
import org.alexmond.kweblens.tui.data.ResourceQuery;

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
		return KeyMap.action(stroke).map((action) -> act(action, stroke)).orElse(Outcome.NONE);
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

	private Outcome submit() {
		String typed = this.prompt.text();
		CommandLineModel.Mode mode = this.prompt.mode();
		this.prompt.close();
		if (mode == CommandLineModel.Mode.FILTER) {
			return filter(typed);
		}
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
			this.message = target.reason();
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
