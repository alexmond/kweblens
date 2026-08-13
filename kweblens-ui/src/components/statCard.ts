import { statusQuery, statusQueryable } from '../objectFilter';
import type { StateCount } from '../types';

/**
 * What a stat card's state list is, once it is decided which of its lines open anything.
 *
 * <p>Kept out of the template because "is this line a link" is a judgement with three separate
 * reasons to be no, and a `v-if` carrying all three is a judgement nobody can test.
 */
export interface CardState extends StateCount {
  /**
   * The filter query this line opens the kind's list with, or null when the line is text.
   *
   * <p>Null is the whole affordance: a `<li>` with a query renders as a button, one without
   * renders as the plain line it has always been. The three ways to be null are a card whose
   * states were never made clickable, a state with nothing in it, and a label the filter
   * grammar cannot express (see {@link statusQueryable}).
   */
  query: string | null;
}

/**
 * The card's states, each told whether it opens anything.
 *
 * <p><b>A zero-count state is never a link.</b> `0 Failed` is worth saying — it is the answer to
 * the question the reader came with — but opening an empty list to say it is strictly worse than
 * the line itself, and it teaches that a state link may lead nowhere.
 */
export function cardStates(states: StateCount[], clickable: boolean): CardState[] {
  return states.map((s) => ({
    ...s,
    query: clickable && s.count > 0 && statusQueryable(s.label) ? statusQuery(s.label) : null,
  }));
}

/**
 * A state link's accessible name: what the line says, plus what pressing it does.
 *
 * <p>The visible text is `80 Running`, which names a quantity and not an action, so a screen
 * reader announcing it as a button would say nothing about where the button goes. The visible
 * text is kept at the FRONT of the name rather than replaced by it (WCAG 2.5.3), so speech
 * control still reaches the control by the words on screen.
 */
export function stateAction(state: CardState, kindLabel: string): string {
  return `${state.count} ${state.label} — show these ${kindLabel}`;
}
