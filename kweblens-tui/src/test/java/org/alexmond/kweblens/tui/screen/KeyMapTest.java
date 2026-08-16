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

		assertThat(rows).hasSize(KeyMap.BINDINGS.size());
		for (KeyBinding binding : KeyMap.BINDINGS) {
			assertThat(rows).contains(binding.hint());
		}
		assertThat(KeyMap.visible()).as("the point of the two projections is that they differ")
			.hasSizeLessThan(KeyMap.BINDINGS.size());
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
