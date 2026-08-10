import { describe, expect, it, vi } from 'vitest';

import type { KubeObject, NavItem } from './types';

// Only `api.del` is stubbed: ApiError stays the real class, because runBulkDelete branches on
// `instanceof ApiError` and a stubbed one would make the 401/403 case pass for the wrong reason.
const { del } = vi.hoisted(() => ({
  del: vi.fn<(cluster: string, resourceId: string, ns: string, name: string) => Promise<unknown>>(() =>
    Promise.resolve(),
  ),
}));
vi.mock('./api', async (orig) => ({ ...((await orig()) as object), api: { del } }));

const { ApiError } = await import('./api');
const { runBulkDelete } = await import('./shell');

const pvs: NavItem = {
  id: 'persistentvolumes',
  label: 'Persistent Volumes',
  kind: 'PersistentVolume',
  namespaced: false,
};
const cms: NavItem = { id: 'configmaps', label: 'Config Maps', kind: 'ConfigMap', namespaced: true };

const clusterScoped = (name: string): KubeObject => ({ kind: 'PersistentVolume', metadata: { name } });
const namespaced = (name: string, ns = 'default'): KubeObject => ({
  kind: 'ConfigMap',
  metadata: { name, namespace: ns },
});

/** objKey() — what the selection set holds. */
const keyOf = (o: KubeObject): string => (o.metadata?.namespace ?? '') + '/' + (o.metadata?.name ?? '');

function deps(over: Record<string, unknown> = {}) {
  return {
    cluster: 'c1',
    selected: cms,
    selection: new Set<string>(),
    objects: [] as KubeObject[],
    dialog: { confirm: () => Promise.resolve(true), prompt: () => Promise.resolve(null) },
    reportOutcome: vi.fn(),
    onAuthCleared: vi.fn(),
    clearSelection: vi.fn(),
    ...over,
  } as unknown as Parameters<typeof runBulkDelete>[0];
}

describe('bulk delete sends cluster-scoped objects', () => {
  // #297: the old filter was `selection.has(objKey(o)) && objNs(o)`, which dropped every
  // Node / PersistentVolume / ClusterRole / cluster-scoped CRD — the button was offered on
  // those lists and then nothing was sent at all.

  it('sends a request per cluster-scoped object, with an empty namespace', async () => {
    del.mockClear();
    const objects = [clusterScoped('pv-a'), clusterScoped('pv-b')];
    const outcome = await runBulkDelete(deps({ selected: pvs, objects, selection: new Set(objects.map(keyOf)) }));

    expect(del.mock.calls).toEqual([
      ['c1', 'persistentvolumes', '', 'pv-a'],
      ['c1', 'persistentvolumes', '', 'pv-b'],
    ]);
    expect(outcome).toMatchObject({ attempted: 2, deleted: ['pv-a', 'pv-b'], failed: [] });
  });

  it('still passes the namespace for a namespaced kind', async () => {
    del.mockClear();
    const objects = [namespaced('cm-a', 'scratch')];
    await runBulkDelete(deps({ objects, selection: new Set(objects.map(keyOf)) }));

    expect(del.mock.calls).toEqual([['c1', 'configmaps', 'scratch', 'cm-a']]);
  });

  it('sends nothing when the confirm is declined', async () => {
    del.mockClear();
    const objects = [clusterScoped('pv-a')];
    const outcome = await runBulkDelete(
      deps({
        selected: pvs,
        objects,
        selection: new Set(objects.map(keyOf)),
        dialog: { confirm: () => Promise.resolve(false) },
      }),
    );

    expect(del).not.toHaveBeenCalled();
    expect(outcome).toBeNull();
  });
});

describe('bulk delete reports what failed', () => {
  // The old catch swallowed everything that was not 401/403: a webhook denial, a 409 from a
  // finalizer, a 500. Seven of ten blocked looked exactly like ten deleted.

  it('surfaces a partial failure with the counts and the reasons', async () => {
    del.mockClear();
    del.mockImplementation((_c, _r, _ns, name) =>
      name === 'cm-b' ? Promise.reject(new ApiError(409, '409 Conflict')) : Promise.resolve(),
    );
    const objects = [namespaced('cm-a'), namespaced('cm-b'), namespaced('cm-c')];
    const reportOutcome = vi.fn();
    const clearSelection = vi.fn();

    const outcome = await runBulkDelete(
      deps({ objects, selection: new Set(objects.map(keyOf)), reportOutcome, clearSelection }),
    );

    expect(del).toHaveBeenCalledTimes(3);
    expect(outcome).toMatchObject({ attempted: 3, deleted: ['default/cm-a', 'default/cm-c'], authCleared: false });
    expect(outcome?.failed).toEqual([{ ref: 'default/cm-b', error: '409 Conflict' }]);
    expect(reportOutcome).toHaveBeenCalledTimes(1);
    const message = reportOutcome.mock.calls[0][0] as string;
    expect(message).toContain('Deleted 2 of 3 Config Maps; 1 failed');
    expect(message).toContain('default/cm-b: 409 Conflict');
    expect(clearSelection).toHaveBeenCalled();
    del.mockImplementation(() => Promise.resolve());
  });

  it('says nothing when every delete succeeded', async () => {
    del.mockClear();
    const objects = [namespaced('cm-a'), namespaced('cm-b')];
    const reportOutcome = vi.fn();
    await runBulkDelete(deps({ objects, selection: new Set(objects.map(keyOf)), reportOutcome }));

    expect(reportOutcome).not.toHaveBeenCalled();
  });

  it('truncates a long list of failures but keeps the counts', async () => {
    del.mockClear();
    del.mockImplementation(() => Promise.reject(new Error('boom')));
    const objects = ['a', 'b', 'c', 'd', 'e'].map((n) => namespaced(n));
    const reportOutcome = vi.fn();
    await runBulkDelete(deps({ objects, selection: new Set(objects.map(keyOf)), reportOutcome }));

    const message = reportOutcome.mock.calls[0][0] as string;
    expect(message).toContain('Deleted 0 of 5 Config Maps; 5 failed');
    expect(message).toContain('and 2 more');
    del.mockImplementation(() => Promise.resolve());
  });
});

describe('bulk delete still stops on an auth failure', () => {
  // 401, or a 403 with no ProblemDetail body — the two shapes kweblens's own security can
  // produce. A CODED 403 is the cluster's verdict and is covered by the test below.
  it.each([401, 403])('clears auth and stops the run on %i', async (status) => {
    del.mockClear();
    del.mockImplementation((_c, _r, _ns, name) =>
      name === 'cm-b' ? Promise.reject(new ApiError(status, `${status} nope`)) : Promise.resolve(),
    );
    const objects = [namespaced('cm-a'), namespaced('cm-b'), namespaced('cm-c')];
    const onAuthCleared = vi.fn();
    const reportOutcome = vi.fn();

    const outcome = await runBulkDelete(
      deps({ objects, selection: new Set(objects.map(keyOf)), onAuthCleared, reportOutcome }),
    );

    expect(onAuthCleared).toHaveBeenCalledTimes(1);
    // cm-c was never tried: the loop breaks rather than firing two more rejected requests.
    expect(del).toHaveBeenCalledTimes(2);
    expect(outcome).toMatchObject({ attempted: 3, deleted: ['default/cm-a'], authCleared: true });
    expect(reportOutcome.mock.calls[0][0]).toContain('the rest were not tried');
    del.mockImplementation(() => Promise.resolve());
  });

  it('does NOT clear auth on a cluster RBAC 403, and keeps deleting the rest', async () => {
    // The service account may be allowed to delete the next object; the login is fine either
    // way. Signing the operator out here ended a working session and hid the reason.
    del.mockClear();
    del.mockImplementation((_c, _r, _ns, name) =>
      name === 'cm-b'
        ? Promise.reject(new ApiError(403, 'configmaps "cm-b" is forbidden: User cannot delete', 'cluster-refused'))
        : Promise.resolve(),
    );
    const objects = [namespaced('cm-a'), namespaced('cm-b'), namespaced('cm-c')];
    const onAuthCleared = vi.fn();
    const reportOutcome = vi.fn();

    const outcome = await runBulkDelete(
      deps({ objects, selection: new Set(objects.map(keyOf)), onAuthCleared, reportOutcome }),
    );

    expect(onAuthCleared).not.toHaveBeenCalled();
    expect(del).toHaveBeenCalledTimes(3);
    expect(outcome).toMatchObject({ attempted: 3, deleted: ['default/cm-a', 'default/cm-c'], authCleared: false });
    expect(reportOutcome.mock.calls[0][0]).toContain('is forbidden: User cannot delete');
    del.mockImplementation(() => Promise.resolve());
  });
});
