import { shallowRef, ref } from 'vue';

import { api } from '../api';
import { failureNotice } from '../apiFailure';
import { loadCluster } from '../prefs';
import type { ClusterInfo } from '../types';

/**
 * Which cluster should be open, given the ids that exist right now.
 *
 * <p>Three rules, in order: stay where you are, else reopen the one you were last on, else
 * take the first — and `null` when there is nothing at all. Every clause is guarded on the
 * id still EXISTING, which is the part that used to be missing on the current selection:
 * removing the cluster you are viewing (from the Clusters page, which is a shipped feature)
 * left `cluster` pointing at a dead id, so the shell kept rendering that cluster's name, its
 * whole nav tree and its stale counts against a registry that no longer had it (GH#298).
 *
 * <p>Pure so it can be tested without a DOM or a server; the composable below is the only
 * thing that owns when to call it.
 */
export function selectCluster(current: string | null, remembered: string | null, ids: string[]): string | null {
  if (current !== null && ids.includes(current)) {
    return current;
  }
  if (remembered !== null && ids.includes(remembered)) {
    return remembered;
  }
  return ids[0] ?? null;
}

/** The clusters list + the currently-selected cluster (defaults to the first). `refresh` re-runs
 *  the fetch — the shell calls it after a fresh sign-in so closed-mode data appears without a reload.
 *
 *  `loaded` turns true once the first fetch has settled, either way. Without it an empty list
 *  cannot be told apart from a request still in flight, and the shell would announce "no
 *  clusters registered" (GH#298) for the moment before the answer arrives — a wrong statement
 *  that later corrects itself. */
export function useClusters(setError: (e: string | null) => void) {
  const clusters = shallowRef<ClusterInfo[]>([]);
  const cluster = ref<string | null>(null);
  const loaded = ref(false);
  const refresh = () =>
    api
      .clusters()
      .then((cs) => {
        clusters.value = cs;
        cluster.value = selectCluster(
          cluster.value,
          loadCluster(),
          cs.map((c) => c.id),
        );
      })
      .catch((e) => setError(failureNotice(e)))
      .finally(() => (loaded.value = true));
  refresh();
  return { clusters, cluster, loaded, refresh };
}
