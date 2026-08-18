package org.alexmond.kweblens.tui.screen;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>The gate for "the key list is derived from the bindings".</b>
 *
 * <p>
 * The failure this exists for is not a bug anyone writes on purpose: a binding is added
 * by someone thinking about a feature, the hint bar is edited by someone thinking about
 * help, and they are never the same afternoon. Every TUI that hand-writes its help
 * eventually ships one that names a key nothing does, or omits one that does something —
 * and both read as correct in review.
 *
 * <p>
 * So the assertions below are <b>both directions</b>: every visible binding's label
 * appears in {@link KeyMap#hints()}, and every word in {@code hints()} that looks like a
 * key is a binding's label. A one-directional test passes on a hint bar that has grown a
 * key the table never had.
 *
 * <p>
 * <b>How this was proved to fail.</b> {@code hints()} was temporarily replaced with the
 * hand-written string {@code ResourceScreen} carried before this ticket ("j/k move ·
 * ctrl-d/u page · g/G ends · q quit"), which is exactly the state a drifted help screen
 * is in. {@link #everyVisibleBindingIsInTheHintBar()} then failed on the first binding it
 * checked ({@code : command}) and {@link #theHintBarNamesNothingThatIsNotBound()} failed
 * on {@code move}. Restoring the derivation made both pass without either test changing.
 */
class KeyMapTest {

	@Test
	void everyVisibleBindingIsInTheHintBar() {
		String hints = KeyMap.hints();

		for (KeyBinding binding : KeyMap.visible()) {
			assertThat(hints).as("the hint bar must name every visible binding: " + binding.action())
				.contains(binding.hint());
		}
	}

	@Test
	void theHintBarNamesNothingThatIsNotBound() {
		Set<String> labels = new HashSet<>();
		for (KeyBinding binding : KeyMap.visible()) {
			labels.add(binding.hint());
		}

		List<String> shown = new ArrayList<>(List.of(KeyMap.hints().split(KeyMap.SEPARATOR)));

		assertThat(shown).as("nothing in the bar that no binding produced")
			.allSatisfy((hint) -> assertThat(labels).as("hint bar shows '" + hint + "'").contains(hint));
		assertThat(shown).hasSize(KeyMap.visible().size());
	}

	@Test
	void theHelpPaneCarriesEveryBinding_includingTheOnesTheBarHasNoRoomFor() {
		List<String> rows = KeyMap.helpRows();

		assertThat(rows).hasSize(KeyMap.BINDINGS.size() + KeyMap.PANE_BINDINGS.size() + KeyMap.LOG_BINDINGS.size());
		for (KeyBinding binding : KeyMap.BINDINGS) {
			assertThat(rows).contains(binding.hint());
		}
		for (KeyBinding binding : KeyMap.PANE_BINDINGS) {
			assertThat(rows).as("a key that exists only inside the detail pane is still written down")
				.contains(KeyMap.PANE_PREFIX + binding.hint());
		}
		for (KeyBinding binding : KeyMap.LOG_BINDINGS) {
			assertThat(rows).as("and the same for the log pane's own keys")
				.contains(KeyMap.LOG_PREFIX + binding.hint());
		}
		assertThat(KeyMap.visible()).as("the point of the two projections is that they differ")
			.hasSizeLessThan(KeyMap.BINDINGS.size());
	}

	/**
	 * <b>The same two directions, over the detail pane's own table</b> (GH#368). The pane
	 * is a document and not a list, so it binds different keys; what must not differ is
	 * that its hint bar is derived from its bindings rather than written by hand.
	 *
	 * <p>
	 * <b>How this was proved to fail.</b> {@code paneHints()} was temporarily pointed at
	 * {@code BINDINGS} instead of {@code PANE_BINDINGS} — the copy-paste a second table
	 * invites. {@link #everyVisiblePaneBindingIsInThePaneHintBar()} failed on
	 * {@code n next
	 * match} and {@link #thePaneHintBarNamesNothingThatIsNotBoundInThePane()} failed on
	 * {@code : command}, which is precisely the key the pane must not appear to offer.
	 */
	@Test
	void everyVisiblePaneBindingIsInThePaneHintBar() {
		String hints = KeyMap.paneHints();

		for (KeyBinding binding : KeyMap.visible(KeyMap.PANE_BINDINGS)) {
			assertThat(hints).as("the pane's hint bar must name every visible pane binding: " + binding.action())
				.contains(binding.hint());
		}
	}

	@Test
	void thePaneHintBarNamesNothingThatIsNotBoundInThePane() {
		Set<String> labels = new HashSet<>();
		for (KeyBinding binding : KeyMap.visible(KeyMap.PANE_BINDINGS)) {
			labels.add(binding.hint());
		}

		List<String> shown = new ArrayList<>(List.of(KeyMap.paneHints().split(KeyMap.SEPARATOR)));

		assertThat(shown)
			.allSatisfy((hint) -> assertThat(labels).as("pane hint bar shows '" + hint + "'").contains(hint));
		assertThat(shown).hasSize(KeyMap.visible(KeyMap.PANE_BINDINGS).size());
	}

	@Test
	void everyBoundPaneStrokeResolvesToItsOwnAction() {
		for (KeyBinding binding : KeyMap.PANE_BINDINGS) {
			for (KeyStroke stroke : binding.strokes()) {
				assertThat(KeyMap.paneAction(stroke)).as(binding.label() + " -> " + binding.action())
					.contains(binding.action());
			}
		}
	}

	/**
	 * The tables are genuinely separate. A pane that answered {@code :} would open a
	 * command line over a table nobody is looking at, and a list that answered {@code n}
	 * would move a cursor through matches of a search that does not exist there.
	 */
	@Test
	void theTablesDoNotAnswerEachOthersKeys() {
		assertThat(KeyMap.paneAction(KeyStroke.of(':'))).as("the pane has no command line").isEmpty();
		assertThat(KeyMap.paneAction(KeyStroke.of('d'))).as("the pane is already the detail").isEmpty();
		assertThat(KeyMap.action(KeyStroke.of('n'))).as("the list has nothing to search through").isEmpty();
		assertThat(KeyMap.action(KeyStroke.of('N'))).isEmpty();
		assertThat(KeyMap.logAction(KeyStroke.of(':'))).as("nor does the log pane").isEmpty();
		assertThat(KeyMap.logAction(KeyStroke.of('l'))).as("the log pane is already the log").isEmpty();
		assertThat(KeyMap.action(KeyStroke.of('c'))).as("there is no container to switch on a list").isEmpty();
		assertThat(KeyMap.action(KeyStroke.of('t'))).as("nor a stream to re-open with timestamps").isEmpty();
	}

	/**
	 * <b>The same two directions, over the log pane's own table</b> (GH#369). It is a
	 * third screen, not a variant of the detail pane: a log keeps growing, so {@code c}
	 * picks another container, {@code t} re-opens the stream, and {@code /} — which the
	 * detail pane binds — is not offered because nothing here searches yet.
	 *
	 * <p>
	 * <b>How this was proved to fail.</b> {@code logHints()} was temporarily pointed at
	 * {@code PANE_BINDINGS} — the copy-paste a third table invites even more than a
	 * second. {@link #everyVisibleLogBindingIsInTheLogHintBar()} failed on
	 * {@code c next container} and
	 * {@link #theLogHintBarNamesNothingThatIsNotBoundInTheLogPane()} failed on
	 * {@code / search}, which is exactly the key the log pane must not appear to offer.
	 */
	@Test
	void everyVisibleLogBindingIsInTheLogHintBar() {
		String hints = KeyMap.logHints();

		for (KeyBinding binding : KeyMap.visible(KeyMap.LOG_BINDINGS)) {
			assertThat(hints).as("the log pane's hint bar must name every visible log binding: " + binding.action())
				.contains(binding.hint());
		}
	}

	@Test
	void theLogHintBarNamesNothingThatIsNotBoundInTheLogPane() {
		Set<String> labels = new HashSet<>();
		for (KeyBinding binding : KeyMap.visible(KeyMap.LOG_BINDINGS)) {
			labels.add(binding.hint());
		}

		List<String> shown = new ArrayList<>(List.of(KeyMap.logHints().split(KeyMap.SEPARATOR)));

		assertThat(shown)
			.allSatisfy((hint) -> assertThat(labels).as("log hint bar shows '" + hint + "'").contains(hint));
		assertThat(shown).hasSize(KeyMap.visible(KeyMap.LOG_BINDINGS).size());
	}

	@Test
	void everyBoundLogStrokeResolvesToItsOwnAction() {
		for (KeyBinding binding : KeyMap.LOG_BINDINGS) {
			for (KeyStroke stroke : binding.strokes()) {
				assertThat(KeyMap.logAction(stroke)).as(binding.label() + " -> " + binding.action())
					.contains(binding.action());
			}
		}
	}

	/**
	 * The keys that open the two log readings are bound, and the visible one is in the
	 * bar.
	 */
	@Test
	void theLogKeysAreInTheListsTable() {
		assertThat(KeyMap.action(KeyStroke.of('l'))).contains(KeyAction.LOGS);
		assertThat(KeyMap.action(KeyStroke.of('p'))).contains(KeyAction.PREVIOUS_LOGS);
		assertThat(KeyMap.hints()).contains("l logs");
		assertThat(KeyMap.helpRows()).as("p is hidden for width, which is not the same as undocumented")
			.contains("p previous run's logs");
	}

	/**
	 * The hint bar has to fit a terminal. 132 columns is what {@code ScreenHarness} draws
	 * into and what the #364 measurements were taken at; this is why {@code p} is hidden
	 * on the list's table and it is asserted rather than remembered.
	 */
	@Test
	void theHintBarStillFitsATerminal() {
		assertThat(KeyMap.hints().length()).isLessThanOrEqualTo(132);
		assertThat(KeyMap.paneHints().length()).isLessThanOrEqualTo(132);
		assertThat(KeyMap.logHints().length()).isLessThanOrEqualTo(132);
	}

	/** The key that opens the pane is bound, visible, and says what it does. */
	@Test
	void theDetailKeyIsInTheListsTableAndItsHintBar() {
		assertThat(KeyMap.action(KeyStroke.of('d'))).contains(KeyAction.DETAIL);
		assertThat(KeyMap.hints()).contains("d detail");
	}

	@Test
	void everyBoundStrokeResolvesToItsOwnAction() {
		for (KeyBinding binding : KeyMap.BINDINGS) {
			for (KeyStroke stroke : binding.strokes()) {
				assertThat(KeyMap.action(stroke)).as(binding.label() + " -> " + binding.action())
					.contains(binding.action());
			}
		}
	}

	@Test
	void aStrokeNobodyBoundCostsNothing() {
		assertThat(KeyMap.action(KeyStroke.of('~'))).isEmpty();
		assertThat(KeyMap.action(KeyStroke.key(KeyStroke.Kind.TAB))).as("tab belongs to the prompt, not the table")
			.isEmpty();
	}

	@Test
	void theDigitsAreOneBindingAndAllTenOfThemAreBound() {
		for (char digit = '0'; digit <= '9'; digit++) {
			assertThat(KeyMap.action(KeyStroke.of(digit))).contains(KeyAction.NAMESPACE_FAVOURITE);
		}
	}

}
