import { onBeforeUnmount, onMounted } from 'vue';

/**
 * Call `handler` whenever Escape is pressed while the component is mounted — deciding from the
 * state the key was pressed IN, not the state the same keypress has already produced.
 *
 * The listener is registered in the CAPTURE phase on `window`, which is the FIRST position in
 * the whole propagation path (window capture → document capture → … → the target → … →
 * document bubble → window bubble). That is load-bearing rather than tidiness, and it is what
 * GH#488 cost:
 *
 * A caller's guard is a reactive flag it owns (`Detail.vue`'s `yamlEditing`: "the YAML editor
 * is open, so this Escape is the editor's, not the drawer's"). An overlay above it clears that
 * flag by closing itself, synchronously, on the very keypress being judged. naive-ui routes an
 * overlay's own Escape through vueuc's `FocusTrap`, which registers through **evtd** — and
 * evtd registers every handler as `window.addEventListener(type, unified, capture === true)`,
 * whatever element was named. So naive's Escape and a plain `window` keydown listener were
 * BOTH window-bubble listeners, and the winner was whichever registered first. It was naive's.
 * Measured on one keypress with the editor open, in this order:
 *
 *   window capture   →   YamlTab.closeEditor   →   Detail.useEscapeKey (yamlEditing: false)
 *
 * — so the guard read a `false` its own keypress had just written, and one Escape closed the
 * editor and the drawer under it, dropping the object and its `row-active` with it. In the
 * capture phase the flag is still the one the reader saw when they pressed the key, and the
 * overlay above still closes on the same keypress, one phase later.
 *
 * Why the drawer hand-rolls this at all, rather than using `NDrawer`'s own `close-on-esc`:
 * naive gives Escape to the innermost overlay via `FocusTrap`'s stack, and a trap only joins
 * that stack when it is not `disabled` — `!showMask || !trapFocus` for a drawer. The detail
 * drawer is deliberately maskless and non-focus-trapping (it is a panel beside the page, not
 * a modal over it — GH#480), so it is never on that stack and `close-on-esc` never fires for
 * it. Turning the mask back on to inherit the behaviour would undo #480.
 */
export function useEscapeKey(handler: () => void): void {
  const onKey = (e: KeyboardEvent) => {
    if (e.key === 'Escape') {
      handler();
    }
  };
  onMounted(() => window.addEventListener('keydown', onKey, true));
  onBeforeUnmount(() => window.removeEventListener('keydown', onKey, true));
}
