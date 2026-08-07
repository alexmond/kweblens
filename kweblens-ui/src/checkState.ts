// ---- "We could not check" is not "there is nothing wrong" ----
//
// The cluster overview used to render `warnings.value = []` from the events call's `.catch`,
// which made a failed request indistinguishable from a quiet cluster: the stat card said
// **0 Warnings**, in the calm colour, over the sentence "No warnings." An operator opening
// that page during an outage — the moment events are the thing they came for — was told, in
// the product's own voice, that nothing was wrong.
//
// A two-state `T[] | null` cannot carry the difference; `null` is already "still loading",
// so the failure has nowhere to go but into the success shape. Three states can, which is
// the same reason `serverPreview.ts` models a refusal separately from an error and why a
// list row's withheld value renders as `—` and never as an empty string (#276).
//
// The rule, stated once so it is quotable: a count is a claim about the cluster. Only make
// it when the cluster answered.

/**
 * A check that is running, has answered, or could not be run.
 *
 * <p>`unchecked` carries the reason rather than a bare flag — the surface has to be able to
 * say why, and "could not check" with no cause is only marginally better than a wrong zero.
 */
export type CheckState<T> =
  { status: 'checking' } | { status: 'checked'; data: T } | { status: 'unchecked'; message: string };

/** The data, or null while checking / after a failure. Never a fabricated empty. */
export function checkedData<T>(state: CheckState<T>): T | null {
  return state.status === 'checked' ? state.data : null;
}

/**
 * What a stat card should show.
 *
 * <p>`…` while the answer is in flight, the number once it arrives, and `—` when it never
 * did. `—` is the same glyph the tables use for "not sent" and reads as an absent value,
 * which is exactly the claim: we do not know.
 */
export function checkedValue(state: CheckState<unknown[]>): number | string {
  switch (state.status) {
    case 'checking':
      return '…';
    case 'checked':
      return state.data.length;
    case 'unchecked':
      return '—';
  }
}

/**
 * Whether the card may wear its danger styling.
 *
 * <p>Deliberately false for `unchecked`: colouring an unknown as alarming is the mirror of
 * the bug this file exists to stop, and the note beside it already says the check failed.
 */
export function checkedDanger(state: CheckState<unknown[]>): boolean {
  return state.status === 'checked' && state.data.length > 0;
}

/**
 * The line to render instead of an all-clear, or null when there is nothing to explain.
 *
 * <p>`subject` names the check in the words of the page ("warnings", "events"), so the
 * sentence stands on its own beside whatever else the page is saying.
 */
export function uncheckedNote(state: CheckState<unknown>, subject: string): string | null {
  return state.status === 'unchecked'
    ? `Could not check ${subject} — this is unknown, not clear: ${state.message}`
    : null;
}
