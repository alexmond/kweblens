import { describe, expect, it, vi } from 'vitest';

import type { KubeObject } from './types';

vi.mock('./api', () => ({
  api: { del: vi.fn(() => Promise.resolve()) },
}));

const { dispatchRowAction } = await import('./shell');

const obj = (name: string, ns = 'default'): KubeObject => ({
  kind: 'Pod',
  metadata: { name, namespace: ns },
});

/** Deps with everything stubbed; the caller overrides what the case is about. */
function deps(over: Record<string, unknown> = {}) {
  return {
    cluster: 'c1',
    authUser: 'admin',
    dialog: { confirm: () => Promise.resolve(true), prompt: () => Promise.resolve(null) },
    openDock: vi.fn(),
    openLogs: vi.fn(),
    setForward: vi.fn(),
    setDetail: vi.fn(),
    setError: vi.fn(),
    setObjects: vi.fn(),
    setShowLogin: vi.fn(),
    ...over,
  };
}

/** Let the confirm promise and the api.del promise both settle. */
const settle = () => new Promise((r) => setTimeout(r, 0));

describe('delete closes the drawer only when it is showing the deleted object', () => {
  // #233 put Delete inside the drawer. A drawer left open on an object that no longer
  // exists is a detail view of nothing, and it keeps offering actions against it.

  it('closes the drawer when the deleted object is the one on screen', async () => {
    const setDetail = vi.fn();
    const target = obj('web-1');
    dispatchRowAction('pods', 'delete', target, undefined, deps({ setDetail, detailKey: 'default/web-1' }) as never);
    await settle();
    await settle();
    expect(setDetail).toHaveBeenCalledWith(null);
  });

  it('leaves the drawer alone when a DIFFERENT object is deleted', async () => {
    // Deleting a row from the list while the drawer shows something else must not shut it.
    const setDetail = vi.fn();
    dispatchRowAction(
      'pods',
      'delete',
      obj('web-1'),
      undefined,
      deps({ setDetail, detailKey: 'default/api-9' }) as never,
    );
    await settle();
    await settle();
    expect(setDetail).not.toHaveBeenCalled();
  });

  it('does nothing extra when the drawer is shut', async () => {
    const setDetail = vi.fn();
    dispatchRowAction('pods', 'delete', obj('web-1'), undefined, deps({ setDetail, detailKey: null }) as never);
    await settle();
    await settle();
    expect(setDetail).not.toHaveBeenCalled();
  });

  it('still removes the object from the list in every case', async () => {
    const setObjects = vi.fn();
    dispatchRowAction('pods', 'delete', obj('web-1'), undefined, deps({ setObjects, detailKey: null }) as never);
    await settle();
    await settle();
    expect(setObjects).toHaveBeenCalled();
    // The updater drops the deleted object and keeps the rest.
    const updater = setObjects.mock.calls[0][0] as (p: KubeObject[]) => KubeObject[];
    expect(updater([obj('web-1'), obj('api-9')]).map((o) => o.metadata?.name)).toEqual(['api-9']);
  });
});
