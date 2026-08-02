import { beforeEach, describe, expect, it, vi } from 'vitest';

import { useNavCollapse } from './useNavCollapse';

// The nav collapse (#237) is a preference, so the thing worth testing is that it survives a
// reload and never decides anything on its own. No DOM involved — the composable is state
// plus localStorage, and the rendering lives in Sidebar.vue.

const store = new Map<string, string>();

beforeEach(() => {
  store.clear();
  vi.stubGlobal('localStorage', {
    getItem: (k: string) => store.get(k) ?? null,
    setItem: (k: string, v: string) => void store.set(k, v),
    removeItem: (k: string) => void store.delete(k),
  });
});

describe('nav collapse', () => {
  it('starts expanded — the app never collapses the nav on the user behalf', () => {
    expect(useNavCollapse().collapsed.value).toBe(false);
  });

  it('toggles and survives a reload', () => {
    const first = useNavCollapse();
    first.toggle();
    expect(first.collapsed.value).toBe(true);

    // A fresh call is what a page load does.
    expect(useNavCollapse().collapsed.value).toBe(true);

    first.toggle();
    expect(useNavCollapse().collapsed.value).toBe(false);
  });

  it('names the action rather than the state, so the control is never ambiguous', () => {
    const nav = useNavCollapse();
    expect(nav.toggleLabel.value).toBe('Collapse navigation');
    // The reassurance belongs on the collapsing click, not the reopening one.
    expect(nav.toggleTitle.value).toContain('Ctrl/Cmd-K');

    nav.toggle();
    expect(nav.toggleLabel.value).toBe('Show navigation');
    expect(nav.toggleTitle.value).not.toContain('Ctrl/Cmd-K');
  });

  it('degrades to expanded when localStorage is unavailable', () => {
    vi.stubGlobal('localStorage', {
      getItem: () => {
        throw new Error('blocked');
      },
      setItem: () => {
        throw new Error('blocked');
      },
      removeItem: () => {
        throw new Error('blocked');
      },
    });
    const nav = useNavCollapse();
    expect(nav.collapsed.value).toBe(false);
    expect(() => nav.toggle()).not.toThrow();
    expect(nav.collapsed.value).toBe(true);
  });
});
