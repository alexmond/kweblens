import type { Ref } from 'vue';
import { watch } from 'vue';

import { api } from '../api';
import { failureNotice } from '../apiFailure';
import { NAV, allNavItems, helmScopeFailure, loadFavorites, withSyntheticNav } from '../shell';
import type { HelmRelease, NavCategory } from '../types';
import { clusterScoped } from './clusterScoped';

/** Per-cluster nav tree, count badges, namespaces, Helm releases, and the active Helm scope. */
export function useClusterScope(
  cluster: Ref<string | null>,
  namespace: Ref<string | null>,
  helmRelease: Ref<{ namespace: string; name: string } | null>,
  setError: (e: string | null) => void,
  /**
   * Bumped by the shell's Retry. A dependency of the two watches whose failures reach the
   * shell's error slot — the nav tree and the Helm scope — so pressing Retry re-runs exactly
   * what could have put a message there. The counts and the Helm badges swallow their own
   * errors and are left to their existing triggers.
   */
  reload?: Ref<number>,
) {
  // EVERY ref below is declared through `clusterScoped`, and that is the whole point (GH#323):
  // emptying on a cluster change is a property of the declaration, so it cannot be forgotten
  // for a ref added later. It was forgotten for `helmCounts`, which sat beside four refs that
  // were blanked at the top of the watch below and was reset only when the id went null — so a
  // switch to an unreachable cluster kept the previous cluster's Helm badge on the nav.
  const nav = clusterScoped<NavCategory[]>(cluster, () => []);
  const counts = clusterScoped<Record<string, number>>(cluster, () => ({}));
  const helmCounts = clusterScoped<Record<string, number>>(cluster, () => ({}));
  const namespaces = clusterScoped<string[]>(cluster, () => []);
  // Whether that list is an ANSWER. The picker can live with an empty list, but the cluster
  // overview counts it on a stat card, and "0 Namespaces" for a request that failed is the
  // same false all-clear as "0 Warnings" was (checkState.ts).
  const namespacesKnown = clusterScoped(cluster, () => false);
  const helmReleaseList = clusterScoped<HelmRelease[]>(cluster, () => []);
  // Not blanked but re-read: favourites are stored per cluster and need no request.
  const favorites = clusterScoped(cluster, (id) => (id ? loadFavorites(id) : []));
  const helmScope = clusterScoped<Set<string> | null>(cluster, () => null);

  watch(
    [cluster, () => reload?.value],
    ([c]) => {
      if (!c) {
        return;
      }
      // No blanking here: the refs above empty themselves on a cluster change, before this
      // runs. A Retry (the `reload` nonce) deliberately does NOT empty them — it re-asks the
      // same cluster, so the last good tree stays up under the error.
      setError(null);
      api
        .nav(c)
        .then((cats) => (nav.value = withSyntheticNav(cats)))
        .catch((e) => setError(failureNotice(e)));
      api
        .namespaces(c)
        .then((ns) => {
          namespaces.value = ns.map((r) => r.name).sort();
          namespacesKnown.value = true;
        })
        .catch(() => (namespaces.value = []));
      api
        .helmReleases(c)
        .then((r) => (helmReleaseList.value = r))
        .catch(() => (helmReleaseList.value = []));
    },
    { immediate: true },
  );

  watch(
    [cluster, helmRelease, () => reload?.value],
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
        // Fails CLOSED, and says so — see helmScopeFailure. Swallowing this rendered the
        // whole app as an empty shell that looked exactly like a release owning nothing.
        .catch((e) => {
          if (cancelled) {
            return;
          }
          helmScope.value = new Set();
          setError(helmScopeFailure(hr.namespace, hr.name, e));
        });
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
        // Built fresh, so a call that REJECTED contributes no entry and its badge is simply
        // absent — "we did not get a number", which is what the nav should say. Filling in only
        // the fulfilled entries on top of whatever was there is what carried a badge across a
        // cluster switch; it is safe here only because the ref was emptied on that switch.
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

  return { nav, counts, helmCounts, namespaces, namespacesKnown, helmReleaseList, favorites, helmScope };
}
