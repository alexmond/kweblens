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
import { containerQuery, execSocketUrl, logBaseUrl } from '../dock';

const props = defineProps<{ cluster: string; session: DockSession; visible: boolean }>();

const container = ref(props.session.containers[0] ?? '');

// --- terminal state ---
const termHost = ref<HTMLDivElement | null>(null);
let fit: { fit: () => void } | null = null;

// --- logs state ---
const lines = ref<string[]>([]);
const wrap = ref(false);
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
    startTerminal(active).then((c) => (active.cancelled ? c() : (cleanup = c)));
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

/** Logs lifecycle: tail snapshot (fetch) then follow (SSE); rebuilds on the same deps. */
function initLogs() {
  watch(
    () => [props.cluster, props.session.namespace, props.session.pod, container.value],
    (_now, _prev, onCleanup) => {
      let cancelled = false;
      lines.value = [];
      const base = logBaseUrl(props.cluster, props.session);
      const cq = containerQuery(container.value);
      fetch(`${base}?${cq}tailLines=500`)
        .then((r) => r.text())
        .then((t) => {
          if (!cancelled) {
            lines.value = t ? t.replace(/\n$/, '').split('\n') : [];
          }
        })
        .catch(() => undefined);
      const es = new EventSource(`${base}/stream?${cq}`);
      es.onmessage = (e) => {
        if (!cancelled) {
          lines.value = [...lines.value, e.data].slice(-5000);
        }
      };
      onCleanup(() => {
        cancelled = true;
        es.close();
      });
    },
    { immediate: true },
  );
  // Keep the viewport pinned to the newest line.
  watch(
    lines,
    () => {
      const el = logBody.value;
      if (el) {
        el.scrollTop = el.scrollHeight;
      }
    },
    { flush: 'post' },
  );
}

if (props.session.kind === 'terminal') {
  initTerminal();
} else {
  initLogs();
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

    <div v-else class="dock-session">
      <div class="dock-toolbar">
        <select v-if="session.containers.length > 1" v-model="container" class="dock-select">
          <option v-for="c in session.containers" :key="c" :value="c">{{ c }}</option>
        </select>
        <label class="dock-toggle"> <input v-model="wrap" type="checkbox" /> wrap </label>
      </div>
      <div ref="logBody" class="term-body log-body" :class="{ wrap }">
        <div v-if="lines.length === 0" class="log-line dim">(no output yet)</div>
        <div v-for="(l, i) in lines" :key="i" class="log-line">{{ l }}</div>
      </div>
    </div>
  </div>
</template>
