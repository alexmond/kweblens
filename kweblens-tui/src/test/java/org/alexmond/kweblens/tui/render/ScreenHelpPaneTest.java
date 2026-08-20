package org.alexmond.kweblens.tui.render;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import org.junit.jupiter.api.Test;

import org.alexmond.kweblens.tui.detail.DetailModel;
import org.alexmond.kweblens.tui.filter.FilterHelp;
import org.alexmond.kweblens.tui.filter.ObjectFilter;
import org.alexmond.kweblens.tui.screen.HelpPane;
import org.alexmond.kweblens.tui.screen.KeyMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>The gate for "the help is on the screen, and it is the help the parser
 * implements"</b> (GH#470).
 *
 * <p>
 * {@link FilterHelp} existed for a year as a {@code public} constant nothing in the
 * application read: the browser rendered its copy and the terminal rendered none of it,
 * so a TUI operator had no way to learn the filter grammar short of reading the source. A
 * test that the constant <em>parses</em> ({@code ObjectFilterErrorTest}) cannot see that,
 * because it is true of a constant nobody displays.
 *
 * <h2>Read off the painted screen, not off the model</h2>
 *
 * Every assertion below is made against {@link FakeBackend#screenLines()} — the cells
 * TamboUI actually pushed at the terminal — because the defect being pinned is precisely
 * a correct model that reaches no screen. The pane is longer than a terminal, so the
 * captures are taken while paging through it and unioned in the order they appeared;
 * {@link #theRigCanSeeWhatIsOnTheScreen()} is the positive control for the instrument.
 *
 * <h2>Both directions, as {@code KeyMapTest} does it</h2>
 *
 * A row of either source missing from the pane fails, <b>and</b> a row on the pane that
 * no source produced fails — the second is the half a "the pane is not empty" test omits,
 * and it is the one that catches a stale hand-typed copy. The grammar section is held to
 * a third thing besides: every example it shows is fed to {@link ObjectFilter}, so the
 * pane cannot advertise a form the parser refuses.
 *
 * <p>
 * <b>How this was proved to fail — four ways, against a deliberately broken
 * {@code HelpPane}.</b> Dropping the {@code ~wbp} row from the grammar section:
 * {@code but could not find the following elements: ["~wbp"]}. Adding one bogus row
 * ({@code key>1}, a form {@code TermParser} refuses by name):
 * {@code but some elements were not expected: ["key>1"]}, and with the assertions
 * reordered the parse check beside it fails on its own —
 * {@code [key>1] Expecting value to be false but was true}. Skipping a binding and adding
 * an invented one: {@code but some elements were not found: ["p previous run's logs"] and
 * others were not expected: ["z undo"]}.
 *
 * <p>
 * <b>And the instrument was wrong first.</b> {@link #open} originally waited for one more
 * frame to be drawn rather than for the pane's own heading to appear, and the capture it
 * released on was the pod table — the initial frame's diff was still in flight when the
 * key was pressed. It read as a content bug (a section running into the next one) and was
 * a measurement bug. A count of frames is not a statement about what is in them.
 */
class ScreenHelpPaneTest {

	/** Header and frame title above the body; hint bar and footer below it. */
	private static final int BODY_FIRST = 2;

	private static final int BODY_LAST = ScreenHarness.HEIGHT - 2;

	/** The hint bar: the fourth of the five rows the layout splits the frame into. */
	private static final int HINTS_ROW = ScreenHarness.HEIGHT - 2;

	/**
	 * The positive control: a case whose answer is already known. The header is on every
	 * frame, so a capture that cannot find it cannot be trusted to report a help pane
	 * missing either.
	 *
	 * <p>
	 * Awaited rather than read straight after {@code start}, because the harness returns
	 * on the first <em>render</em> and the diff reaches the backend after that.
	 */
	@Test
	void theRigCanSeeWhatIsOnTheScreen() throws Exception {
		try (ScreenHarness harness = ScreenHarness.start(new FakeCluster().withObjects(3), 500)) {
			harness.await(() -> painted(harness).contains("kweblens-tui"), "the header to be painted");

			assertThat(painted(harness)).contains("kweblens-tui").contains("pods").contains("obj-00000");
		}
	}

	private static String painted(ScreenHarness harness) {
		return String.join("\n", harness.backend().screenLines());
	}

	@Test
	void everyBindingTheHelpRowsDeclareIsOnTheScreenAndNothingElseIs() throws Exception {
		try (ScreenHarness harness = ScreenHarness.start(new FakeCluster().withObjects(3), 500)) {
			List<String> pane = openAndPageThrough(harness);

			assertThat(section(pane, HelpPane.KEYS_HEADING))
				.as("the keys on the pane are the binding tables, in both directions")
				.containsExactlyElementsOf(KeyMap.helpRows());
		}
	}

	@Test
	void theGrammarOnScreenIsExactlyTheGrammarFilterHelpDeclares() throws Exception {
		try (ScreenHarness harness = ScreenHarness.start(new FakeCluster().withObjects(3), 500)) {
			List<String> pane = openAndPageThrough(harness);

			List<String> examples = examples(pane);
			assertThat(examples).as("every row of the grammar, and no row that is not one")
				.containsExactlyElementsOf(FilterHelp.ROWS.stream().map(FilterHelp.Row::example).toList());
			assertThat(examples).as("and the pane may not advertise a form the parser refuses")
				.allSatisfy((example) -> assertThat(ObjectFilter.parse(example).failed()).as("%s", example).isFalse());
		}
	}

	@Test
	void theProseThatSaysWhereTheGrammarIsNarrowerThanKubectlIsOnTheScreenToo() throws Exception {
		try (ScreenHarness harness = ScreenHarness.start(new FakeCluster().withObjects(3), 500)) {
			String notes = normalise(String.join(" ", openAndPageThrough(harness)));

			for (String note : FilterHelp.NOTES) {
				assertThat(notes).as("a note wrapped onto the screen is still the note").contains(normalise(note));
			}
		}
	}

	/**
	 * The pane is a list, so it builds widgets for the visible window only (#364) — the
	 * same rule the table and the detail pane are held to, and the reason the help could
	 * grow past a viewport at all.
	 */
	@Test
	void thePaneIsWindowedRatherThanBuiltWhole() throws Exception {
		try (ScreenHarness harness = ScreenHarness.start(new FakeCluster().withObjects(3), 500)) {
			open(harness);
			DetailModel document = harness.screen().controller().helpDocument();

			assertThat(document.size()).as("the point of the windowing is that there is more than a screen of it")
				.isGreaterThan(ScreenHarness.HEIGHT);
			assertThat(harness.screen().rowsBuiltLastFrame()).isLessThanOrEqualTo(BODY_LAST - BODY_FIRST);
		}
	}

	/** Nothing on the pane is wider than the terminal it is drawn into. */
	@Test
	void everyLineFitsTheTerminal() throws Exception {
		try (ScreenHarness harness = ScreenHarness.start(new FakeCluster().withObjects(3), 500)) {
			open(harness);

			assertThat(harness.screen().controller().helpDocument().lines())
				.allSatisfy((line) -> assertThat(line.text().length()).as("%s", line.text())
					.isLessThanOrEqualTo(ScreenHarness.WIDTH));
		}
	}

	/**
	 * <b>The bar under the pane is the pane's own</b> (GH#476).
	 *
	 * <p>
	 * Read off the painted row rather than off {@code screen.hints()}, for the reason
	 * every other assertion in this class is: the defect is a bar an operator reads, and
	 * a method returning the right string to nobody is the same shape of bug as a correct
	 * model nobody renders. The negative half is the issue's own sentence — while the
	 * pane was up the footer offered {@code : command}, {@code ↵ drill in} and
	 * {@code d detail}, and not one of them did that.
	 *
	 * <p>
	 * The control waits for the row to be <em>painted at all</em> and then asserts what
	 * is in it, rather than waiting for the string it is about to assert — the harness
	 * returns on the first {@code render} and the diff reaches the backend after that, so
	 * a bare read here measured an empty row on the first run of this test. No second
	 * wait after {@link #open}: it already waits for the pane's heading to be painted,
	 * and the heading and the bar are drawn by one {@code render} into one diff, so the
	 * row below is that same frame's.
	 *
	 * <p>
	 * <b>How this was proved to fail — twice, and one of them is the shipped bug.</b>
	 * Deleting the {@code help()} branch from {@code ResourceScreen.hints()}, i.e. the
	 * state GH#476 was filed against: {@code expected: "j/↓ down a line · … · esc/q close
	 * (as does any other key)" but was: ": command · / filter · ↵ drill in · esc/q back ·
	 * …"}. And pointing {@code KeyMap.helpHints()} at {@code BINDINGS}, which fails the
	 * negative half on its own: {@code [and names none of the list's keys, which here
	 * only close the pane] … not to contain: ": command"}.
	 */
	@Test
	void theBarUnderThePaneIsThePanesOwnKeysAndNotTheListsBar() throws Exception {
		try (ScreenHarness harness = ScreenHarness.start(new FakeCluster().withObjects(3), 500)) {
			harness.await(() -> !hintBar(harness).isEmpty(), "the hint bar to be painted");
			assertThat(hintBar(harness)).as("the positive control: the list really does show the list's bar")
				.isEqualTo(KeyMap.hints());

			open(harness);
			String bar = hintBar(harness);

			assertThat(bar).as("the ? pane's bar is the projection of the ? pane's table")
				.isEqualTo(KeyMap.helpHints());
			assertThat(bar).as("and names none of the list's keys, which here only close the pane")
				.doesNotContain(": command")
				.doesNotContain("↵ drill in")
				.doesNotContain("d detail")
				.doesNotContain("l logs");
		}
	}

	/**
	 * The escape hatch is unchanged by the pane having grown a cursor: the keys that
	 * scroll scroll, and every other key still closes.
	 */
	@Test
	void aScrollKeyScrollsAndAnyOtherKeyCloses() throws Exception {
		try (ScreenHarness harness = ScreenHarness.start(new FakeCluster().withObjects(3), 500)) {
			open(harness);
			DetailModel document = harness.screen().controller().helpDocument();

			press(harness, KeyCode.PAGE_DOWN);
			harness.await(() -> document.selectedIndex() > 0, "the pane to scroll rather than close");
			assertThat(harness.screen().controller().help()).isTrue();

			press(harness, 'x');
			harness.await(() -> !harness.screen().controller().help(), "any other key to close the pane");
		}
	}

	/**
	 * <b>The other half of the wrong-table mistake: the dispatcher</b> (GH#476).
	 * {@code :} is the first key on the list's bar and the pane binds nothing for it, so
	 * it must be an "any other key" and close. A {@link HelpPane} reading the
	 * <em>list's</em> table instead of its own passes every bar assertion in this class
	 * and fails here — it would resolve {@code :} to a command line, decline to act on
	 * it, and leave the pane up under a bar that never offered it.
	 *
	 * <p>
	 * <b>How this was proved to fail.</b> {@code HelpPane.key} was pointed back at
	 * {@code KeyMap.action}: {@code waited 10s over 58907 passes for : to close the pane
	 * like any other key, and it never happened}. Every bar assertion above stayed green
	 * throughout, which is the point of having this case as well as those.
	 */
	@Test
	void aKeyTheListBindsButThePaneDoesNotIsJustAnotherKeyThatCloses() throws Exception {
		try (ScreenHarness harness = ScreenHarness.start(new FakeCluster().withObjects(3), 500)) {
			open(harness);

			press(harness, ':');

			harness.await(() -> !harness.screen().controller().help(), ": to close the pane like any other key");
			assertThat(harness.screen().controller().prompt().open())
				.as("and it does not open a command line over the list it just uncovered")
				.isFalse();
		}
	}

	/**
	 * And the key the bar actually names closes it too (GH#476). Worth its own case
	 * because {@code esc}/{@code q} now takes a different arm from every other key — a
	 * {@code BACK} row in the pane's table rather than the fall-through — so "the bar
	 * says esc/q" and "esc/q closes" stopped being the same line of code.
	 */
	@Test
	void theKeyTheBarNamesClosesIt() throws Exception {
		try (ScreenHarness harness = ScreenHarness.start(new FakeCluster().withObjects(3), 500)) {
			open(harness);

			press(harness, 'q');

			harness.await(() -> !harness.screen().controller().help(), "q, the key the bar names, to close the pane");
			assertThat(harness.running()).as("and it closes the pane rather than quitting the app").isTrue();
		}
	}

	private static void press(ScreenHarness harness, char character) {
		harness.runner().dispatch(KeyEvent.ofChar(character));
	}

	private static void press(ScreenHarness harness, KeyCode code) {
		harness.runner().dispatch(KeyEvent.ofKey(code));
	}

	/**
	 * Open the pane and wait until it is <b>on the screen</b>.
	 *
	 * <p>
	 * Waited for the pane's own first line, not for a frame count: the initial table
	 * frame's diff can still be in flight when the key is pressed, so "one more draw than
	 * before" was satisfied by the screen the pane was about to replace — measured, the
	 * first capture was the pod table. A count of frames is not a statement about what is
	 * in them.
	 */
	private static void open(ScreenHarness harness) {
		press(harness, '?');
		harness.await(() -> harness.screen().controller().help(), "the help pane to open");
		harness.await(() -> body(harness).stream().anyMatch((line) -> line.contains(HelpPane.KEYS_HEADING)),
				"the help pane to be painted");
	}

	/** The hint bar of the last painted frame — the row GH#476 is about. */
	private static String hintBar(ScreenHarness harness) {
		return harness.backend().screenLines().get(HINTS_ROW).strip();
	}

	/** The body of the last painted frame, without the chrome around it. */
	private static List<String> body(ScreenHarness harness) {
		List<String> painted = harness.backend().screenLines();
		return painted.subList(BODY_FIRST, Math.min(BODY_LAST, painted.size()));
	}

	/**
	 * Open the pane and page to the bottom, collecting what was on screen at each stop.
	 *
	 * <p>
	 * The captures overlap, so they are unioned by a set that keeps insertion order —
	 * which is the pane, in the order an operator scrolling through it would read.
	 */
	private static List<String> openAndPageThrough(ScreenHarness harness) {
		open(harness);
		DetailModel document = harness.screen().controller().helpDocument();
		Set<String> seen = new LinkedHashSet<>();
		while (true) {
			seen.addAll(body(harness));
			int before = document.selectedIndex();
			if (before >= document.size() - 1) {
				return new ArrayList<>(seen);
			}
			int painted = harness.backend().draws();
			press(harness, KeyCode.PAGE_DOWN);
			harness.await(() -> document.selectedIndex() > before, "the pane to scroll past line " + before);
			harness.await(() -> harness.backend().draws() > painted, "the frame the scroll owes");
		}
	}

	/**
	 * The rows of one section: everything indented under {@code heading}, up to the blank
	 * line or the next heading that ends it, stripped of the indent.
	 */
	private static List<String> section(List<String> pane, String heading) {
		List<String> rows = new ArrayList<>();
		boolean inside = false;
		for (String line : pane) {
			if (line.contains(heading)) {
				inside = true;
			}
			else if (inside && (line.isBlank() || !line.startsWith(HelpPane.INDENT))) {
				return rows;
			}
			else if (inside) {
				rows.add(line.strip());
			}
		}
		return rows;
	}

	/**
	 * The example column of the grammar section — what the pane claims is a query. Split
	 * on the padding, which is the same gap a reader's eye uses; no example contains two
	 * consecutive spaces.
	 */
	private static List<String> examples(List<String> pane) {
		List<String> examples = new ArrayList<>();
		for (String row : section(pane, HelpPane.FILTER_HEADING)) {
			if (!row.startsWith(HelpPane.COLUMN_HEADING)) {
				examples.add(row.split(" {2,}")[0]);
			}
		}
		return examples;
	}

	private static String normalise(String text) {
		return text.replaceAll("\\s+", " ").strip().toLowerCase(Locale.ROOT);
	}

}
