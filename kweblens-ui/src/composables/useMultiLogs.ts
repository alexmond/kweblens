import { computed, onBeforeUnmount, ref, shallowRef, watch } from 'vue';

import type { DockSession, LogScope } from '../dock';
import { multiLogStreamUrl, sourceLabels } from '../dock';

/** One rendered log line, already attributed to the source that produced it. */
export type MultiLogLine = {
  /** `namespace/pod/container` — the key for colour, filtering and show/hide. */
  source: string;
  /** RFC3339 from the API server, or null when it supplied none. */
  timestamp: string | null;
  text: string;
};

/** A source the stream is following, with its assigned gutter colour. */
export type MultiLogSource = {
  id: string;
  label: string;
  colour: string;
  visible: boolean;
  /** Set when this source alone could not be followed. */
  error?: string;
  /**
   * The stream is no longer following this source — its pod is gone, typically replaced by a
   * rollout. Kept in the registry rather than removed, because its lines are still in the
   * buffer and need their colour, label and visibility toggle.
   */
  gone?: boolean;
};

/**
 * Distinct hues for the source gutter. Chosen to stay legible on the dark log background
 * and to remain distinguishable for the most common red/green colour-vision deficiency —
 * so the colour is a fast grouping cue, never the only way to tell sources apart (the
 * text prefix always carries the name).
 */
const PALETTE = [
  '#5eb0ef',
  '#2e9e8f',
  '#c9a227',
  '#c026a8',
  '#8b7fe8',
  '#e07a3f',
  '#4bb3a0',
  '#d45d79',
  '#7aa5d2',
  '#9d8f2f',
];

/** Global cap across ALL sources — N streams multiply the burst, so the budget is shared. */
const MAX_LOG_LINES = 5000;

/**
 * Follows one multiplexed log stream (one container, a whole pod, or a whole workload) and
 * exposes the lines plus the source registry.
 *
 * Lines are BUFFERED and flushed at most once per animation frame, never one reactive
 * update per message. This is the same invariant the resource-list watch relies on, and it
 * matters more here: with N sources the arrival rate is N times higher, so per-message
 * rendering would freeze the tab exactly when a rollout makes the logs interesting.
 */
export function useMultiLogs(
  cluster: () => string,
  session: () => DockSession,
  container: () => string,
  paused: () => boolean,
) {
  const lines = shallowRef<MultiLogLine[]>([]);
  const sources = shallowRef<MultiLogSource[]>([]);
  const truncated = ref<{ shown: number; totalFound: number } | null>(null);
  const filter = ref('');
  const error = ref<string | null>(null);

  const scope = (): LogScope => session().logScope ?? 'container';

  const setVisible = (id: string, visible: boolean) => {
    sources.value = sources.value.map((s) => (s.id === id ? { ...s, visible } : s));
  };
  const showOnly = (id: string) => {
    sources.value = sources.value.map((s) => ({ ...s, visible: s.id === id }));
  };
  const showAll = () => {
    sources.value = sources.value.map((s) => ({ ...s, visible: true }));
  };

  const colourOf = (id: string) => sources.value.find((s) => s.id === id)?.colour ?? '#8b98a5';
  const labelOf = (id: string) => sources.value.find((s) => s.id === id)?.label ?? id;

  /**
   * What the pane renders: hidden sources and non-matching text are filtered out here
   * rather than at ingest, so toggling a source or editing the filter re-reveals lines
   * already received instead of only affecting future ones.
   */
  const visibleLines = computed(() => {
    const hidden = new Set(sources.value.filter((s) => !s.visible).map((s) => s.id));
    const needle = filter.value.trim().toLowerCase();
    return lines.value.filter((l) => !hidden.has(l.source) && (needle === '' || l.text.toLowerCase().includes(needle)));
  });

  /** Whether to show the per-line source prefix at all — pointless when following one source. */
  const showPrefix = computed(() => sources.value.length > 1);

  let pending: MultiLogLine[] = [];
  let frame = 0;
  const flush = () => {
    frame = 0;
    if (pending.length === 0) {
      return;
    }
    const next = lines.value.concat(pending);
    pending = [];
    lines.value = next.length > MAX_LOG_LINES ? next.slice(-MAX_LOG_LINES) : next;
  };

  watch(
    // The container only matters in `container` scope; including it unconditionally would
    // reconnect the whole workload stream every time the dropdown moved.
    () => [cluster(), session().namespace, session().pod, scope(), session().workload?.name, container()],
    (_now, _prev, onCleanup) => {
      let cancelled = false;
      // Monotonic across the connection so a source that joins later never takes a colour
      // still in use by one already on screen.
      let colourCursor = 0;
      pending = [];
      lines.value = [];
      sources.value = [];
      truncated.value = null;
      error.value = null;

      const es = new EventSource(multiLogStreamUrl(cluster(), session(), container()));

      es.addEventListener('sources', (e) => {
        if (cancelled) {
          return;
        }
        const data = JSON.parse((e as MessageEvent).data) as {
          sources: string[];
          truncated: boolean;
          totalFound: number;
        };
        // The set CHANGES mid-stream — a rollout replaces every pod behind a workload — so
        // this is a UNION, not a rebuild.
        //
        // A source already known keeps its colour, visibility and error: reassigning colours
        // would recolour lines already on screen, and would silently undo a source the user
        // had hidden. A source that has GONE is kept too, marked `gone`, because its lines
        // are still in the buffer — dropping it would leave them grey and unfilterable
        // during a rollout, which is exactly when both halves need reading together.
        const live = new Set(data.sources);
        const known = new Set(sources.value.map((s) => s.id));
        const merged: MultiLogSource[] = sources.value.map((s) => ({ ...s, gone: !live.has(s.id) }));
        for (const id of data.sources) {
          if (!known.has(id)) {
            merged.push({ id, label: id, colour: PALETTE[colourCursor++ % PALETTE.length], visible: true });
          }
        }
        // Labels are relative to the whole set, not per id — telling replicas apart needs to
        // know what prefix they share — so they are recomputed whenever the set changes.
        const labels = sourceLabels(
          merged.map((s) => s.id),
          scope(),
        );
        sources.value = merged.map((s, i) => ({ ...s, label: labels[i] }));
        truncated.value = data.truncated ? { shown: data.sources.length, totalFound: data.totalFound } : null;
      });

      es.addEventListener('line', (e) => {
        if (cancelled || paused()) {
          return;
        }
        pending.push(JSON.parse((e as MessageEvent).data) as MultiLogLine);
        if (frame === 0) {
          frame = requestAnimationFrame(flush);
        }
      });

      // One unfollowable container must not blank the view, so it is surfaced against its
      // own row in the legend and the other sources keep streaming.
      es.addEventListener('source-error', (e) => {
        if (cancelled) {
          return;
        }
        const data = JSON.parse((e as MessageEvent).data) as { source: string; message: string };
        sources.value = sources.value.map((s) => (s.id === data.source ? { ...s, error: data.message } : s));
      });

      // A source that failed and later attached — a pod that had not finished starting. The
      // error is cleared rather than left behind, so the legend does not keep accusing a
      // source that is streaming perfectly well.
      es.addEventListener('source-recovered', (e) => {
        if (cancelled) {
          return;
        }
        const data = JSON.parse((e as MessageEvent).data) as { source: string };
        sources.value = sources.value.map((s) => (s.id === data.source ? { ...s, error: undefined } : s));
      });

      es.onerror = () => {
        if (!cancelled && sources.value.length === 0) {
          error.value = 'Could not open the log stream.';
        }
      };

      onCleanup(() => {
        cancelled = true;
        es.close();
        if (frame !== 0) {
          cancelAnimationFrame(frame);
          frame = 0;
        }
        pending = [];
      });
    },
    { immediate: true },
  );

  onBeforeUnmount(() => {
    if (frame !== 0) {
      cancelAnimationFrame(frame);
    }
  });

  /** The visible buffer as plain text, for copy/download. */
  const asText = () =>
    visibleLines.value.map((l) => (showPrefix.value ? `[${labelOf(l.source)}] ${l.text}` : l.text)).join('\n');

  return {
    lines,
    visibleLines,
    sources,
    truncated,
    filter,
    error,
    showPrefix,
    colourOf,
    labelOf,
    setVisible,
    showOnly,
    showAll,
    asText,
  };
}
