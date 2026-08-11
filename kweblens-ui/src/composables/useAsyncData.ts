import type { Ref } from 'vue';
import { failureNotice } from '../apiFailure';
import { ref, shallowRef, watch } from 'vue';

/**
 * Loads data for a pane, keeping "loading", "loaded" and "failed" as three distinct states.
 *
 * <p>The bug this exists to remove: every pane used `data === null` to mean BOTH "not loaded
 * yet" and "the request failed", and bound its table's spinner to that. So a failed request
 * showed an error banner over a spinner that ran forever, with the empty state stuck on
 * "Loading…" — two contradictory answers on screen at once. Three states cannot be encoded
 * in one nullable value, so `loading` is its own flag and stops whatever the outcome.
 *
 * <p>The sequence guard replaces the hand-rolled `cancelled` flag each pane carried: a slow
 * response for a cluster you have already navigated away from must not overwrite the data
 * for the one you are looking at now.
 *
 * <p><b>`deps` is the IDENTITY of what is being loaded, not merely a list of reload triggers</b>
 * (GH#323). The two are different questions and were being answered the same way. A failed
 * `reload()` keeps the last good data, because a retry of the same request is still about the
 * same thing and showing those rows under an error beats blanking the pane. A failed load after
 * `deps` CHANGED cannot keep it, because the rows are then a statement about something else:
 * a cluster switch to an unreachable API server left `DiagnosisPanel`'s header saying "11
 * critical, 19 warning" — the previous cluster's counts — directly above its own body saying the
 * request timed out. So `data` is cleared when `deps` change and kept across `reload()`, which
 * makes every pane built on this composable correct without each one remembering to be.
 *
 * <p>The corollary for call sites: <b>do not put a refresh nonce in `deps`</b>. A counter bumped
 * after a mutation is a reload of the same question — call `reload()`.
 */
export interface AsyncData<T> {
  /** The loaded value, or null before the first success. Null does NOT imply loading. */
  data: Ref<T | null>;
  /** True only while a request is in flight. False once it succeeds OR fails. */
  loading: Ref<boolean>;
  /** The failure message, or null. Cleared when a retry starts. */
  error: Ref<string | null>;
  /** Load the SAME thing again — what a Retry control calls. Keeps the last good data. */
  reload: () => void;
}

/**
 * @param deps the identity of what is being loaded, in the shape `watch` expects. Changing it
 * discards the previous value; it is not a refresh trigger (see above)
 * @param load performs the request
 * @param onError optional hook for failures the pane handles specially, e.g. an expired
 * session that should sign the user out rather than render as a table error
 */
export function useAsyncData<T>(
  deps: () => unknown,
  load: () => Promise<T>,
  onError?: (e: unknown) => void,
): AsyncData<T> {
  const data = shallowRef<T | null>(null) as Ref<T | null>;
  const loading = ref(true);
  const error = ref<string | null>(null);
  let seq = 0;

  const reload = () => {
    const mine = ++seq;
    loading.value = true;
    error.value = null;
    load()
      .then((value) => {
        if (mine === seq) {
          data.value = value;
        }
      })
      .catch((e: unknown) => {
        if (mine !== seq) {
          return;
        }
        // The data is deliberately left as it was rather than cleared: on a retry that
        // fails, showing the last good rows under an error beats blanking the pane. That
        // holds only because the identity did not change — the watch below discards the
        // value first when it did, so this can never leave another cluster's answer up.
        error.value = failureNotice(e);
        onError?.(e);
      })
      .finally(() => {
        if (mine === seq) {
          loading.value = false;
        }
      });
  };

  // Discard first, THEN load. Between the two the pane renders its loading state, which is the
  // only honest thing it can say about a cluster it has not asked yet.
  watch(
    deps,
    () => {
      data.value = null;
      reload();
    },
    { immediate: true },
  );
  return { data, loading, error, reload };
}
