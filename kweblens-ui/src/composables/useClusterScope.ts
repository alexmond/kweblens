import type { Ref } from 'vue';
import { ref, watch } from 'vue';

import { api } from '../api';
import { NAV, allNavItems, loadFavorites, withSyntheticNav } from '../shell';
import type { HelmRelease, NavCategory } from '../types';

/** Per-cluster nav tree, count badges, namespaces, Helm releases, and the active Helm scope. */
export function useClusterScope(
  cluster: Ref<string | null>,
  namespace: Ref<string | null>,
  helmRelease: Ref<{ namespace: string; name: string } | null>,
  setError: (e: string | null) => void,
) {
  const nav = ref<NavCategory[]>([]);
  const counts = ref<Record<string, number>>({});
  const helmCounts = ref<Record<string, number>>({});
  const namespaces = ref<string[]>([]);
  const helmReleaseList = ref<HelmRelease[]>([]);
  const favorites = ref<string[]>([]);
  const helmScope = ref<Set<string> | null>(null);

  watch(
    cluster,
    (c) => {
      if (!c) {
        return;
      }
      nav.value = [];
      counts.value = {};
      favorites.value = loadFavorites(c);
      namespaces.value = [];
      helmReleaseList.value = [];
      setError(null);
      api
        .nav(c)
        .then((cats) => (nav.value = withSyntheticNav(cats)))
        .catch((e) => setError(String(e)));
      api
        .namespaces(c)
        .then((ns) => (namespaces.value = ns.map((r) => r.name).sort()))
        .catch(() => (namespaces.value = []));
      api
        .helmReleases(c)
        .then((r) => (helmReleaseList.value = r))
        .catch(() => (helmReleaseList.value = []));
    },
    { immediate: true },
  );

  watch(
    [cluster, helmRelease],
    ([c, hr], _prev, onCleanup) => {
      if (!c || !hr) {
        helmScope.value = null;
        return;
      }
      let cancelled = false;
      onCleanup(() => (cancelled = true));
      api
        .helmReleaseResources(c, hr.namespace, hr.name)
        .then((refs) => !cancelled && (helmScope.value = new Set(refs.map((r) => `${r.kind}/${r.name}`))))
        .catch(() => !cancelled && (helmScope.value = new Set()));
    },
    { immediate: true },
  );

  watch(
    [cluster, namespace, helmScope, nav],
    ([c, ns, scope], _prev, onCleanup) => {
      if (!c) {
        return;
      }
      if (scope) {
        const kindToId = new Map<string, string>();
        allNavItems(nav.value).forEach((i) => i.kind && kindToId.set(i.kind, i.id));
        const result: Record<string, number> = {};
        scope.forEach((key) => {
          const id = kindToId.get(key.split('/')[0]);
          if (id) {
            result[id] = (result[id] ?? 0) + 1;
          }
        });
        counts.value = result;
        return;
      }
      let cancelled = false;
      onCleanup(() => (cancelled = true));
      api
        .counts(c, ns ?? undefined)
        .then((r) => !cancelled && (counts.value = r))
        .catch(() => !cancelled && (counts.value = {}));
    },
    { immediate: true },
  );

  watch(
    cluster,
    (c, _prev, onCleanup) => {
      if (!c) {
        helmCounts.value = {};
        return;
      }
      let cancelled = false;
      onCleanup(() => (cancelled = true));
      Promise.allSettled([
        api.helmReleases(c).then((r) => r.length),
        api.helmRepos().then((r) => r.length),
        api.helmCharts(c).then((r) => r.length),
      ]).then(([releases, repos, charts]) => {
        if (cancelled) {
          return;
        }
        const result: Record<string, number> = {};
        if (releases.status === 'fulfilled') {
          result[NAV.helmReleases] = releases.value;
        }
        if (repos.status === 'fulfilled') {
          result[NAV.helmRepositories] = repos.value;
        }
        if (charts.status === 'fulfilled') {
          result[NAV.helmCharts] = charts.value;
        }
        helmCounts.value = result;
      });
    },
    { immediate: true },
  );

  return { nav, counts, helmCounts, namespaces, helmReleaseList, favorites, helmScope };
}
