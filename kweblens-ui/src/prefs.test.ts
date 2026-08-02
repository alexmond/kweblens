import { beforeEach, describe, expect, it, vi } from 'vitest';

import {
  loadCluster,
  loadDark,
  loadHiddenCols,
  loadNamespace,
  loadNavCollapsed,
  saveCluster,
  saveDark,
  saveHiddenCols,
  saveNamespace,
  saveNavCollapsed,
} from './prefs';

// Preferences must never throw into a render: a corrupt value or a browser that refuses
// localStorage (private mode, storage full) has to degrade to defaults silently.

const store = new Map<string, string>();

beforeEach(() => {
  store.clear();
  vi.stubGlobal('localStorage', {
    getItem: (k: string) => store.get(k) ?? null,
    setItem: (k: string, v: string) => void store.set(k, v),
    removeItem: (k: string) => void store.delete(k),
  });
});

describe('hidden columns', () => {
  it('falls back to the kind defaults when nothing is saved, then remembers a change', () => {
    // The papercut this fixes: the choice used to be re-seeded from defaults on every
    // navigation, so an opt-in column was lost as soon as you moved away.
    const defaults = new Set(['osImage', 'kernel']);
    expect([...loadHiddenCols('nodes', defaults)]).toEqual(['osImage', 'kernel']);

    saveHiddenCols('nodes', new Set(['kernel']));
    expect([...loadHiddenCols('nodes', defaults)]).toEqual(['kernel']);
  });

  it('keeps an EMPTY choice distinct from "nothing saved"', () => {
    // Showing every column is a real preference; if empty collapsed back to the fallback,
    // un-hiding the last column would silently re-hide it on the next visit.
    saveHiddenCols('nodes', new Set());
    expect([...loadHiddenCols('nodes', new Set(['osImage']))]).toEqual([]);
  });

  it('is per kind, and ignores a missing kind', () => {
    saveHiddenCols('nodes', new Set(['a']));
    expect([...loadHiddenCols('pods', new Set(['b']))]).toEqual(['b']);
    expect([...loadHiddenCols(undefined, new Set(['b']))]).toEqual(['b']);
  });
});

describe('cluster and namespace', () => {
  it('remembers the namespace PER CLUSTER', () => {
    // Namespaces are cluster-local: carrying one across clusters would filter on a namespace
    // that may not exist there and silently show an empty list.
    saveNamespace('prod', 'monitoring');
    saveNamespace('dev', null);
    expect(loadNamespace('prod')).toBe('monitoring');
    expect(loadNamespace('dev')).toBeNull();
    expect(loadNamespace('other')).toBeNull();
  });

  it('round-trips the last cluster', () => {
    expect(loadCluster()).toBeNull();
    saveCluster('prod');
    expect(loadCluster()).toBe('prod');
  });
});

describe('theme', () => {
  it('defaults to dark', () => {
    expect(loadDark()).toBe(true);
  });

  it('migrates the legacy kw-theme key and then removes it', () => {
    store.set('kw-theme', 'light');
    expect(loadDark()).toBe(false);
    expect(store.has('kw-theme')).toBe(false); // migrated, not left behind
    expect(loadDark()).toBe(false); // and the migrated value sticks
  });

  it('round-trips an explicit choice', () => {
    saveDark(false);
    expect(loadDark()).toBe(false);
    saveDark(true);
    expect(loadDark()).toBe(true);
  });
});

describe('nav collapse', () => {
  it('defaults to expanded and round-trips a choice', () => {
    expect(loadNavCollapsed()).toBe(false);
    saveNavCollapsed(true);
    expect(loadNavCollapsed()).toBe(true);
    saveNavCollapsed(false);
    expect(loadNavCollapsed()).toBe(false);
  });
});

describe('resilience', () => {
  it('returns the fallback for a corrupt value instead of throwing', () => {
    store.set('kw.cols.nodes', '{not json');
    expect([...loadHiddenCols('nodes', new Set(['osImage']))]).toEqual(['osImage']);
  });

  it('survives a localStorage that refuses reads and writes', () => {
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
    expect(() => saveHiddenCols('nodes', new Set(['a']))).not.toThrow();
    expect([...loadHiddenCols('nodes', new Set(['b']))]).toEqual(['b']);
    expect(loadDark()).toBe(true);
    expect(loadCluster()).toBeNull();
  });
});
