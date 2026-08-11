import type { Ref } from 'vue';
import { shallowRef, watch } from 'vue';

/**
 * A ref holding an answer ABOUT ONE CLUSTER, emptied the instant the question changes.
 *
 * <p>The bug this exists to remove (GH#323): switching to a cluster whose API server was not
 * there left the Helm nav badge reading `50` and the Diagnosis header reading `11 critical,
 * 19 warning` — real numbers, about the cluster the operator had just left, rendered next to
 * "Could not reach the cluster". A number that is merely absent is read as "not known yet";
 * a number that is present and wrong is acted on.
 *
 * <p>The mechanism matters more than the two values it fixes. The previous shape was a list
 * of assignments at the top of a watch — `nav = []`, `counts = {}`, `namespaces = []` — and a
 * list is exactly what goes stale: `helmCounts` was declared beside them, reset only when the
 * cluster id went null, and nobody noticed for as long as every cluster answered. Declaring
 * the state through this helper makes the reset a property of the DECLARATION, so a per-cluster
 * ref added later cannot be forgotten: there is no list to add it to.
 *
 * <p>Two properties the call sites rely on:
 * <ul>
 * <li><b>The reset lands first.</b> Vue runs watchers in creation order, and this one is created
 * when the ref is declared — before any loader watch further down the composable. So by the time
 * a fetch for the new cluster starts, the old cluster's value is already gone.</li>
 * <li><b>A retry is not a switch.</b> Nothing here reacts to a reload nonce, so pressing Retry
 * against the SAME cluster keeps the last good value under the error, which is the behaviour
 * `useAsyncData` documents for the same reason.</li>
 * </ul>
 *
 * @param cluster the active cluster id, or null when there is none
 * @param initial the empty value for a cluster — receives the new id, for state that is
 * loaded rather than blanked (per-cluster favourites, the remembered namespace)
 */
export function clusterScoped<T>(cluster: Ref<string | null>, initial: (id: string | null) => T): Ref<T> {
  const state = shallowRef(initial(cluster.value)) as Ref<T>;
  watch(cluster, (id) => (state.value = initial(id)));
  return state;
}
