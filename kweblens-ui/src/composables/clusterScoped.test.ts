import { effectScope, nextTick, ref, watch } from 'vue';
import { describe, expect, it } from 'vitest';

import { clusterScoped } from './clusterScoped';

// GH#323: a cluster switch left the previous cluster's Helm badge and diagnosis counts on
// screen. The rule this helper enforces is that per-cluster state empties on a switch because
// of how it was DECLARED, not because someone remembered to add it to a reset list.

describe('clusterScoped', () => {
  it('empties on a cluster switch', async () => {
    const cluster = ref<string | null>('kind-a');
    const scope = effectScope();
    let counts!: ReturnType<typeof clusterScoped<Record<string, number>>>;
    scope.run(() => (counts = clusterScoped<Record<string, number>>(cluster, () => ({}))));

    counts.value = { helmReleases: 50 };
    cluster.value = 'kind-b';
    await nextTick();

    expect(counts.value).toEqual({});
    scope.stop();
  });

  it('empties when the last cluster goes away', async () => {
    // GH#301: `selectCluster` settles on null when nothing is left. Nothing may then write to
    // this ref again, so the switch itself has to be what clears it.
    const cluster = ref<string | null>('kind-a');
    const scope = effectScope();
    let nav!: ReturnType<typeof clusterScoped<string[]>>;
    scope.run(() => (nav = clusterScoped<string[]>(cluster, () => [])));

    nav.value = ['workloads'];
    cluster.value = null;
    await nextTick();

    expect(nav.value).toEqual([]);
    scope.stop();
  });

  it('re-seeds from the new cluster id rather than only blanking', async () => {
    const cluster = ref<string | null>('kind-a');
    const scope = effectScope();
    let favorites!: ReturnType<typeof clusterScoped<string>>;
    scope.run(() => (favorites = clusterScoped(cluster, (id) => `saved:${id}`)));

    expect(favorites.value).toBe('saved:kind-a');
    cluster.value = 'kind-b';
    await nextTick();
    expect(favorites.value).toBe('saved:kind-b');
    scope.stop();
  });

  it('leaves the value alone when nothing about the cluster changed', async () => {
    // A Retry is not a switch: re-asking the same cluster keeps the last good answer under the
    // error, which is the behaviour useAsyncData documents for the same reason.
    const cluster = ref<string | null>('kind-a');
    const scope = effectScope();
    let counts!: ReturnType<typeof clusterScoped<Record<string, number>>>;
    scope.run(() => (counts = clusterScoped<Record<string, number>>(cluster, () => ({}))));

    counts.value = { pods: 12 };
    cluster.value = 'kind-a';
    await nextTick();

    expect(counts.value).toEqual({ pods: 12 });
    scope.stop();
  });

  it('empties BEFORE a loader watch declared after it runs', async () => {
    // The ordering the call sites depend on: Vue runs watchers in creation order, so a fetch
    // started for the new cluster can never observe the old cluster's value still in place.
    const cluster = ref<string | null>('kind-a');
    const scope = effectScope();
    const seen: unknown[] = [];
    scope.run(() => {
      const counts = clusterScoped<Record<string, number>>(cluster, () => ({}));
      counts.value = { helmReleases: 50 };
      watch(cluster, () => seen.push({ ...counts.value }));
    });

    cluster.value = 'kind-b';
    await nextTick();

    expect(seen).toEqual([{}]);
    scope.stop();
  });
});
