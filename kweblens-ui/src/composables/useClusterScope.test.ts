import { effectScope, nextTick, ref } from 'vue';
import { describe, expect, it, vi } from 'vitest';

import { useClusterScope } from './useClusterScope';

// GH#323: switching to a cluster whose API server was not there left the Helm nav badge on
// `50` — the count from the cluster viewed moments earlier.
//
// The issue blamed the `Promise.allSettled` for "filling in only the fulfilled entries", but
// that is not what carried the number: the handler builds a FRESH object every time, so a
// rejected call contributes nothing once it settles. What carried it is the WINDOW. Nothing
// emptied `helmCounts` when the watch started, so the previous cluster's badge stayed up for
// as long as the new cluster's requests were in flight — and against an API server that is not
// there, that is the whole 20-second timeout the screenshot was taken inside, or forever if the
// connection never settles at all. The assertions below are therefore made DURING the window.

const reachable = { releases: 50, repos: 3, charts: 12 };
const helmReleases = vi.fn();
const helmRepos = vi.fn();
const helmCharts = vi.fn();
const namespaces = vi.fn();

vi.mock('../api', () => ({
  api: {
    nav: () => Promise.resolve([]),
    namespaces: (...a: unknown[]) => namespaces(...a),
    counts: () => Promise.resolve({}),
    helmReleases: (...a: unknown[]) => helmReleases(...a),
    helmRepos: (...a: unknown[]) => helmRepos(...a),
    helmCharts: (...a: unknown[]) => helmCharts(...a),
    helmReleaseResources: () => Promise.resolve([]),
  },
}));

const flush = () => new Promise((r) => setTimeout(r, 0));
const rejects = () => Promise.reject(new Error('Could not reach the cluster'));
/** An API server that is simply not there: the request never comes back at all. */
const hangs = () => new Promise<never>(() => undefined);

function scoped() {
  const cluster = ref<string | null>('kind-reachable');
  const namespace = ref<string | null>(null);
  const helmRelease = ref<{ namespace: string; name: string } | null>(null);
  const scope = effectScope();
  let out!: ReturnType<typeof useClusterScope>;
  scope.run(() => (out = useClusterScope(cluster, namespace, helmRelease, () => undefined, ref(0))));
  return { cluster, scope, ...out };
}

describe('useClusterScope Helm badges', () => {
  it('drops the previous cluster badge when the new cluster cannot be reached', async () => {
    namespaces.mockImplementation(() => Promise.resolve([]));
    helmReleases.mockImplementation(() => Promise.resolve(new Array(reachable.releases).fill({})));
    helmRepos.mockImplementation(() => Promise.resolve(new Array(reachable.repos).fill({})));
    helmCharts.mockImplementation(() => Promise.resolve(new Array(reachable.charts).fill({})));

    const { cluster, scope, helmCounts } = scoped();
    await flush();
    expect(Object.values(helmCounts.value)).toContain(reachable.releases);

    // The cluster is not there, so its requests hang. Repositories are configured on the server
    // rather than in the cluster and would answer, but `allSettled` waits for all three — so
    // during this window the honest answer is no badge at all.
    helmReleases.mockImplementation(hangs);
    helmCharts.mockImplementation(hangs);
    cluster.value = 'kind-unreachable';
    await nextTick();

    expect(helmCounts.value).toEqual({});
    scope.stop();
  });

  it('shows a badge only for the Helm calls that answered', async () => {
    namespaces.mockImplementation(() => Promise.resolve([]));
    helmReleases.mockImplementation(rejects);
    helmCharts.mockImplementation(rejects);
    helmRepos.mockImplementation(() => Promise.resolve(new Array(reachable.repos).fill({})));

    const { scope, helmCounts } = scoped();
    await flush();

    // Repositories are not per cluster, so that one is legitimately still a number. The two the
    // cluster owns are absent rather than zero: a `0` beside "Releases" is a claim.
    expect(Object.values(helmCounts.value)).toEqual([reachable.repos]);
    scope.stop();
  });

  it('empties the release list and namespace answer on a switch, before any of them reply', async () => {
    namespaces.mockImplementation(() => Promise.resolve([]));
    helmReleases.mockImplementation(() => Promise.resolve([{ name: 'a' }]));
    helmRepos.mockImplementation(() => Promise.resolve([]));
    helmCharts.mockImplementation(() => Promise.resolve([]));

    const { cluster, scope, helmReleaseList, namespacesKnown } = scoped();
    await flush();
    expect(helmReleaseList.value).toHaveLength(1);
    expect(namespacesKnown.value).toBe(true);

    // Nothing about the new cluster ever answers. "We have not looked yet" and "here is the
    // answer" are different sentences, and the second one must not be left on screen.
    helmReleases.mockImplementation(hangs);
    namespaces.mockImplementation(hangs);
    cluster.value = 'kind-unreachable';
    await nextTick();
    expect(helmReleaseList.value).toEqual([]);
    expect(namespacesKnown.value).toBe(false);
    scope.stop();
  });
});
