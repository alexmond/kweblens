import { createApp, h, ref } from 'vue';
import { afterEach, describe, expect, it } from 'vitest';

import { useEscapeKey } from './useEscapeKey';

// GH#488: Escape with the YAML editor open closed the editor AND the drawer under it, so the
// object being edited vanished and its row lost `row-active`. `Detail.vue` has a guard for
// exactly that (`yamlEditing`), and the guard was reading a value the same keypress had just
// written: naive-ui closes the editor's modal on Escape, which clears the flag synchronously,
// and only then did the drawer's plain `window` bubble listener get to read it.
//
// Both listeners were window-BUBBLE ones — naive's goes through vueuc's FocusTrap, which
// registers through evtd, and evtd registers everything as `window.addEventListener(type,
// unified, capture === true)` whatever element was named — so the winner was whichever
// registered first, and that was naive's. The fix is the capture phase, which is the first
// position in the propagation path and therefore the state the reader actually pressed the
// key in. That is what the second test below measures; without it the fix is a one-character
// change nothing would notice going away.

const cleanups: Array<() => void> = [];

afterEach(() => {
  while (cleanups.length) cleanups.pop()!();
});

/** Mount a render-less component that installs the handler, the way `Detail.vue` does. */
function mount(handler: () => void): () => void {
  const app = createApp({
    setup() {
      useEscapeKey(handler);
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

/** A real keypress from inside the page, so it travels the whole propagation path. */
const press = (key: string) => document.body.dispatchEvent(new KeyboardEvent('keydown', { key, bubbles: true }));

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

  it('reads the guard as the key was pressed, not as an overlay above it left it (#488)', () => {
    // The stand-in for naive's overlay: a window listener in the BUBBLE phase, registered
    // before the drawer mounts — which is the arrangement that produced the bug. It closes
    // itself by clearing the very flag the drawer's handler is about to consult.
    const editorOpen = ref(true);
    const overlayClosesItself = () => (editorOpen.value = false);
    window.addEventListener('keydown', overlayClosesItself);
    cleanups.push(() => window.removeEventListener('keydown', overlayClosesItself));

    const seen: boolean[] = [];
    mount(() => seen.push(editorOpen.value));
    press('Escape');

    // The control: the overlay really did consume this keypress. Without it, a handler that
    // never ran at all would pass the assertion below.
    expect(editorOpen.value).toBe(false);
    expect(seen).toEqual([true]);
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
