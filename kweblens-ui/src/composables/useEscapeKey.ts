import { onBeforeUnmount, onMounted } from 'vue';

/**
 * An OPEN MODAL DIALOG, as the page itself declares one.
 *
 * This is not a naive-ui selector and not a list of this app's overlays. `aria-modal="true"`
 * on a `role="dialog"` is ARIA's own statement that the element is above everything else and
 * that the rest of the page is inert while it is there — which is exactly the question an
 * Escape handler has to answer. Measured on a running instance: the detail drawer renders
 * `role=dialog aria-modal=true`, and so does every `NModal` in this app (the command palette,
 * the YAML editor, the Helm modals, the dialogs, the login). None of them was asked to.
 */
const OPEN_DIALOG = '[role="dialog"][aria-modal="true"]';

/**
 * Is `own` the INNERMOST open dialog — i.e. is Escape its key?
 *
 * The innermost dialog is the LAST one in document order, because an overlay is teleported to
 * the end of its container at the moment it opens, so tree order is open order is stacking
 * order. Measured on a running instance: the detail drawer teleports into `.content-col`,
 * which is inside `#app`, while naive appends each `.n-modal-container` to `<body>` — so a
 * modal opened over the drawer is always later in the document than it, whichever opened
 * first. A dialog nested inside another is later than its container for the same reason.
 * Passing `null` means "I am not a dialog at all", which claims Escape only when no dialog is
 * open. `querySelectorAll` returns tree order, which is the whole reason this is one line.
 *
 * The rule is deliberately about the page rather than about a flag per overlay: a flag is a
 * list that goes stale the next time something is rendered over the drawer, and that list is
 * what GH#497 was. Here a new overlay needs to do nothing to take the key, because declaring
 * itself a dialog is something it has to do anyway to be usable with a screen reader.
 */
export function isInnermostDialog(own: Element | null): boolean {
  const dialogs = document.querySelectorAll(OPEN_DIALOG);
  return dialogs.length === 0 ? own === null : dialogs[dialogs.length - 1] === own;
}

/**
 * Call `handler` whenever Escape is pressed while the component is mounted AND the component
 * is the innermost open dialog — deciding from the state the key was pressed IN, not the
 * state the same keypress has already produced.
 *
 * `ownDialog` resolves the caller's own dialog element (the drawer is teleported, so it is
 * found by selector rather than by template ref). Omitting it means "I am not a dialog".
 *
 * WHY THE PHASE IS LOAD-BEARING (GH#488)
 *
 * The listener is registered in the CAPTURE phase on `window`, which is the FIRST position in
 * the whole propagation path (window capture → document capture → … → the target → … →
 * document bubble → window bubble). naive-ui routes an overlay's own Escape through vueuc's
 * `FocusTrap`, which registers through **evtd** — and evtd registers every handler as
 * `window.addEventListener(type, unified, capture === true)`, whatever element was named. So
 * naive's Escape and a plain `window` keydown listener were BOTH window-bubble listeners, and
 * the winner was whichever registered first. It was naive's. Measured on one keypress with
 * the YAML editor open, in this order:
 *
 *   window capture   →   YamlTab.closeEditor   →   Detail.useEscapeKey
 *
 * — so a reader of the page state read a state its own keypress had just written, and one
 * Escape closed the editor and the drawer under it, dropping the object and its `row-active`
 * with it. In the capture phase the page is still the one the reader saw when they pressed
 * the key, and the overlay above still closes on the same keypress, one phase later.
 *
 * WHY THE GUARD IS A QUESTION AND NOT A FLAG (GH#497)
 *
 * #488's fix was the phase; the guard it fixed the timing of was `Detail.vue`'s `yamlEditing`
 * — one boolean, about one overlay. So the command palette, opened over the drawer with
 * Ctrl/Cmd-K, still took the drawer with it on Escape, and so would anything else ever
 * rendered above the drawer. `isInnermostDialog` replaces the flag entirely: the drawer no
 * longer knows what is above it, only that something is.
 *
 * WHAT THIS COSTS. A naive overlay stays in the DOM for its leave transition — measured at
 * ~300 ms for the palette, `.n-modal` and `[aria-modal]` both still present at +210 ms and
 * gone by +530 ms — so a second Escape inside that window is refused as well, and the reader
 * presses again. That is the trade: the key belongs to the overlay that is still on screen,
 * and the alternative (a reactive registry every overlay must join) buys those 300 ms at the
 * price of a failure mode where a NEW overlay silently closes the drawer again.
 *
 * WHY THE DRAWER HAND-ROLLS THIS AT ALL, rather than using `NDrawer`'s own `close-on-esc`:
 * naive gives Escape to the innermost overlay via `FocusTrap`'s stack, and a trap only joins
 * that stack when it is not `disabled` — `!showMask || !trapFocus` for a drawer. The detail
 * drawer is deliberately maskless and non-focus-trapping (it is a panel beside the page, not
 * a modal over it — GH#480), so it is never on that stack and `close-on-esc` never fires for
 * it. Turning the mask back on to inherit the behaviour would undo #480. That stack is also
 * not readable from here: vueuc keeps it in a module-local `let stack = []` that is not
 * exported and is REASSIGNED on every deactivate, so even a captured reference goes stale.
 */
export function useEscapeKey(handler: () => void, ownDialog: () => Element | null = () => null): void {
  const onKey = (e: KeyboardEvent) => {
    if (e.key === 'Escape' && isInnermostDialog(ownDialog())) {
      handler();
    }
  };
  onMounted(() => window.addEventListener('keydown', onKey, true));
  onBeforeUnmount(() => window.removeEventListener('keydown', onKey, true));
}
