<script setup lang="ts">
/**
 * One dock session pane — an xterm.js exec terminal (`session.kind === 'terminal'`) or a
 * streaming log follower (`session.kind === 'logs'`). The component instance is kept alive
 * by the parent's <Teleport> as it moves between the dock and a floating window, so the
 * WebSocket / log stream survives detach and re-dock (this is why it must never be destroyed
 * on move — only the DOM node is relocated).
 *
 * `visible` toggles display:flex/none — the dock hides inactive tabs; the floating window and
 * the active docked tab are shown. (React set this on the `.session-host` host node.)
 *
 * Ports React `TerminalSession` + `LogsSession` from kweblens-ui/src/dock.tsx. Vue's onMounted
 * runs once (no StrictMode double-mount), so no double-mount guard is needed.
 */
import { onBeforeUnmount, onMounted, ref, watch } from 'vue';

import type { DockSession } from '../dock';
import { execSocketUrl } from '../dock';
import { useMultiLogs } from '../composables/useMultiLogs';

const props = defineProps<{ cluster: string; session: DockSession; visible: boolean }>();

const container = ref(props.session.containers[0] ?? '');

// --- terminal state ---
const termHost = ref<HTMLDivElement | null>(null);
let fit: { fit: () => void } | null = null;

// --- logs state ---
const wrap = ref(false);
const paused = ref(false);
const showLegend = ref(true);
const logBody = ref<HTMLDivElement | null>(null);

/**
 * Build the xterm terminal + exec WebSocket. Faithful to the React wiring: term.onData → ws.send,
 * ws.onmessage → term.write, plus close/error banners. Returns a teardown that closes the socket
 * and disposes the terminal. `signal.cancelled` guards the async import against a torn-down pane.
 */
async function startTerminal(signal: { cancelled: boolean }): Promise<() => void> {
  const [{ Terminal }, { FitAddon }] = await Promise.all([import('@xterm/xterm'), import('@xterm/addon-fit')]);
  if (signal.cancelled || !termHost.value) {
    return () => undefined;
  }
  const term = new Terminal({ fontSize: 13, cursorBlink: true, theme: { background: '#0f172a' } });
  const fitAddon = new FitAddon();
  term.loadAddon(fitAddon);
  term.open(termHost.value);
  try {
    fitAddon.fit();
  } catch {
    // pane not laid out yet; the ResizeObserver refits once it is
  }
  fit = fitAddon;
  const ws = new WebSocket(execSocketUrl(props.cluster, props.session, container.value));
  ws.onmessage = (e) => {
    if (typeof e.data === 'string') {
      term.write(e.data);
    }
  };
  ws.onclose = () => term.write('\r\n\x1b[90m[session closed]\x1b[0m\r\n');
  ws.onerror = () => term.write('\r\n\x1b[31m[connection error]\x1b[0m\r\n');
  term.onData((d) => {
    if (ws.readyState === WebSocket.OPEN) {
      ws.send(d);
    }
  });
  return () => {
    fit = null;
    ws.close();
    term.dispose();
  };
}

/** Terminal lifecycle: (re)build on mount and whenever cluster/namespace/pod/container change. */
function initTerminal() {
  let cleanup = () => undefined as void;
  let signal = { cancelled: false };
  const restart = () => {
    signal.cancelled = true;
    cleanup();
    signal = { cancelled: false };
    const active = signal;
    startTerminal(active).then((c) => {
      if (active.cancelled) {
        c();
      } else {
        cleanup = c;
      }
    });
  };
  onMounted(() => {
    restart();
    // Refit whenever the body changes size — tab shown/hidden, dock resize, float move/resize,
    // window resize (xterm can't measure a hidden or stale-sized element).
    const ro = new ResizeObserver(() => {
      try {
        fit?.fit();
      } catch {
        // not ready to fit yet
      }
    });
    if (termHost.value) {
      ro.observe(termHost.value);
    }
    watch(() => [props.cluster, props.session.namespace, props.session.pod, container.value], restart);
    onBeforeUnmount(() => {
      ro.disconnect();
      signal.cancelled = true;
      cleanup();
    });
  });
}

/**
 * Logs: one multiplexed stream (a container, a whole pod, or a whole workload). All the SSE
 * wiring, the rAF batching and the source registry live in the composable; this component
 * only renders and owns the toolbar state.
 */
const logs =
  props.session.kind === 'logs'
    ? useMultiLogs(
        () => props.cluster,
        () => props.session,
        () => container.value,
        () => paused.value,
      )
    : null;

/** Follow the tail — but never fight the user's scroll while they are reading history. */
const followTail = ref(true);
const onLogScroll = () => {
  const el = logBody.value;
  if (el) {
    followTail.value = el.scrollHeight - el.scrollTop - el.clientHeight < 24;
  }
};

const copyLogs = () => {
  if (logs) {
    navigator.clipboard?.writeText(logs.asText()).catch(() => undefined);
  }
};

if (props.session.kind === 'terminal') {
  initTerminal();
} else if (logs) {
  watch(
    logs.visibleLines,
    () => {
      const el = logBody.value;
      if (el && followTail.value) {
        el.scrollTop = el.scrollHeight;
      }
    },
    { flush: 'post' },
  );
}
</script>

<template>
  <div class="session-host" :style="{ display: visible ? 'flex' : 'none' }">
    <div v-if="session.kind === 'terminal'" class="dock-session">
      <div v-if="session.containers.length > 1" class="dock-toolbar">
        <select v-model="container" class="dock-select">
          <option v-for="c in session.containers" :key="c" :value="c">{{ c }}</option>
        </select>
      </div>
      <div ref="termHost" class="term-body" />
    </div>

    <div v-else-if="logs" class="dock-session">
      <div class="dock-toolbar">
        <!-- The container dropdown only makes sense when following exactly one container;
             in pod/workload scope every container is already in the stream and the legend
             is how you narrow it. -->
        <select
          v-if="(session.logScope ?? 'container') === 'container' && session.containers.length > 1"
          v-model="container"
          class="dock-select"
        >
          <option v-for="c in session.containers" :key="c" :value="c">{{ c }}</option>
        </select>
        <input v-model="logs.filter.value" class="dock-input" type="search" placeholder="filter…" />
        <label class="dock-toggle"> <input v-model="wrap" type="checkbox" /> wrap </label>
        <label class="dock-toggle"> <input v-model="paused" type="checkbox" /> pause </label>
        <button v-if="logs.sources.value.length > 1" class="dock-btn" type="button" @click="showLegend = !showLegend">
          {{ showLegend ? 'hide' : 'show' }} sources ({{ logs.sources.value.length }})
        </button>
        <button class="dock-btn" type="button" title="Copy visible lines" @click="copyLogs">copy</button>
        <span v-if="!followTail" class="dock-note">paused at scroll position</span>
      </div>

      <!-- Source legend: colour key, per-source show/hide, and where a single failing
           container reports itself without blanking the rest of the view. -->
      <div v-if="showLegend && logs.sources.value.length > 1" class="log-legend">
        <button class="log-legend-all" type="button" @click="logs.showAll()">all</button>
        <button
          v-for="s in logs.sources.value"
          :key="s.id"
          class="log-src"
          :class="{ off: !s.visible, failed: !!s.error }"
          type="button"
          :title="s.error ? `${s.id} — ${s.error}` : `${s.id} (click to toggle, double-click to isolate)`"
          @click="logs.setVisible(s.id, !s.visible)"
          @dblclick="logs.showOnly(s.id)"
        >
          <span class="log-src-dot" :style="{ background: s.colour }" />
          {{ s.label }}
        </button>
      </div>

      <div v-if="logs.truncated.value" class="log-warning">
        Following {{ logs.truncated.value.shown }} of {{ logs.truncated.value.totalFound }} matching containers — the
        rest are not being streamed.
      </div>

      <div ref="logBody" class="term-body log-body" :class="{ wrap }" @scroll="onLogScroll">
        <div v-if="logs.error.value" class="log-line error">{{ logs.error.value }}</div>
        <div v-else-if="logs.visibleLines.value.length === 0" class="log-line dim">
          {{ logs.lines.value.length > 0 ? '(no lines match the current filter)' : '(no output yet)' }}
        </div>
        <div v-for="(l, i) in logs.visibleLines.value" :key="i" class="log-line">
          <span v-if="logs.showPrefix.value" class="log-src-tag" :style="{ color: logs.colourOf(l.source) }">{{
            logs.labelOf(l.source)
          }}</span>
          <span>{{ l.text }}</span>
        </div>
      </div>
    </div>
  </div>
</template>
