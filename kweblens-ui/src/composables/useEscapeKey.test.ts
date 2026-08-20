import { createApp, h } from 'vue';
import { afterEach, describe, expect, it } from 'vitest';

import { isInnermostDialog, useEscapeKey } from './useEscapeKey';

// Two tickets, one key, and the second one is why the guard is a question rather than a flag.
//
// GH#488: Escape with the YAML editor open closed the editor AND the drawer under it, so the
// object being edited vanished and its row lost `row-active`. `Detail.vue` had a guard for
// exactly that (`yamlEditing`), and the guard was reading a value the same keypress had just
// written: naive-ui closes the editor's modal on Escape, which cleared the flag synchronously,
// and only then did the drawer's plain `window` bubble listener get to read it. Both listeners
// were window-BUBBLE ones — naive's goes through vueuc's FocusTrap, which registers through
// evtd, and evtd registers everything as `window.addEventListener(type, unified, capture ===
// true)` whatever element was named — so the winner was whichever registered first, and that
// was naive's. The fix was the capture phase, the first position in the propagation path and
// therefore the state the reader actually pressed the key in.
//
// GH#497: the same damage from the command palette, which has no flag — and would have come
// back from the next overlay after that. So the guard is now `isInnermostDialog`, a question
// about the page: is any element that CALLS ITSELF a modal dialog above me. Nothing has to
// register, because `role="dialog" aria-modal="true"` is what an overlay owes a screen reader
// anyway. Both halves are measured below, and the capture phase is still load-bearing: an
// overlay above removes itself from the DOM on the very keypress being judged.

const cleanups: Array<() => void> = [];

afterEach(() => {
  while (cleanups.length) cleanups.pop()!();
});

/** Mount a render-less component that installs the handler, the way `Detail.vue` does. */
function mount(handler: () => void, ownDialog?: () => Element | null): () => void {
  const app = createApp({
    setup() {
      useEscapeKey(handler, ownDialog);
      return () => h('div');
    },
  });
  const host = document.createElement('div');
  document.body.appendChild(host);
  app.mount(host);
  const unmount = () => {
    app.unmount();
    host.remove();
  };
  cleanups.push(unmount);
  return unmount;
}

/** An overlay as the page declares one — the drawer and every `NModal` render exactly this. */
function dialog(cls: string, parent: Element = document.body): HTMLElement {
  const el = document.createElement('div');
  el.className = cls;
  el.setAttribute('role', 'dialog');
  el.setAttribute('aria-modal', 'true');
  parent.appendChild(el);
  cleanups.push(() => el.remove());
  return el;
}

/** A real keypress from inside the page, so it travels the whole propagation path. */
const press = (key: string) => document.body.dispatchEvent(new KeyboardEvent('keydown', { key, bubbles: true }));

describe('isInnermostDialog', () => {
  it('is true when the page declares no dialog at all', () => {
    expect(isInnermostDialog(null)).toBe(true);
  });

  it('is true for the only dialog on the page, and false once one is opened over it', () => {
    const drawer = dialog('drawer');
    expect(isInnermostDialog(drawer)).toBe(true);
    const above = dialog('palette');
    expect(isInnermostDialog(drawer)).toBe(false);
    // …and the one on top still owns it, which is the half that keeps this from being
    // "nobody may ever close anything".
    expect(isInnermostDialog(above)).toBe(true);
  });

  it('gives the key to a dialog nested inside another, not to the one containing it', () => {
    const outer = dialog('drawer');
    const inner = dialog('nested', outer);
    expect(isInnermostDialog(inner)).toBe(true);
    expect(isInnermostDialog(outer)).toBe(false);
  });

  it('is false for a non-dialog caller while any dialog is open', () => {
    dialog('palette');
    expect(isInnermostDialog(null)).toBe(false);
  });
});

describe('useEscapeKey', () => {
  it('calls the handler on Escape and on nothing else', () => {
    let calls = 0;
    mount(() => calls++);
    press('a');
    press('Enter');
    expect(calls).toBe(0);
    press('Escape');
    expect(calls).toBe(1);
  });

  it('does not fire while an overlay is open over it, and fires once that overlay is gone (#497)', () => {
    const drawer = dialog('drawer');
    let calls = 0;
    mount(
      () => calls++,
      () => drawer,
    );

    const palette = dialog('palette');
    press('Escape');
    expect(calls).toBe(0);

    // The other half: "never fires" would pass the assertion above perfectly.
    palette.remove();
    press('Escape');
    expect(calls).toBe(1);
  });

  it('reads the page as the key was pressed, not as an overlay above it left it (#488)', () => {
    // The stand-in for naive's overlay: a window listener in the BUBBLE phase, registered
    // before the drawer mounts — which is the arrangement that produced the bug. It closes
    // itself by leaving the DOM on the very keypress being judged.
    const drawer = dialog('drawer');
    const palette = dialog('palette');
    const overlayClosesItself = () => palette.remove();
    window.addEventListener('keydown', overlayClosesItself);
    cleanups.push(() => window.removeEventListener('keydown', overlayClosesItself));

    let calls = 0;
    mount(
      () => calls++,
      () => drawer,
    );
    press('Escape');

    // The control: the overlay really did consume this keypress. Without it, a handler that
    // never ran at all would pass the assertion beside it.
    expect(palette.isConnected).toBe(false);
    expect(calls).toBe(0);
  });

  it('stops listening once the component unmounts', () => {
    let calls = 0;
    const unmount = mount(() => calls++);
    press('Escape');
    expect(calls).toBe(1);
    unmount();
    press('Escape');
    expect(calls).toBe(1);
  });
});
