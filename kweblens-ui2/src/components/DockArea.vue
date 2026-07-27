<script setup lang="ts">
/**
 * Owns all terminal + log sessions. Docked sessions appear as tabs in a resizable bottom panel;
 * a session can be popped out into a floating, draggable/resizable window and folded back.
 *
 * Each session's body (DockSession) lives in a single stable <Teleport> whose target flips
 * between the dock's `.dock-bodies` and a floating frame's `.float-body`. Vue's Teleport moves
 * the DOM nodes without re-creating the component, so the shell / WebSocket / log stream survives
 * detach and re-dock — the Vue equivalent of the React "stable detached node + appendChild" trick.
 *
 * Ports React `DockArea` from kweblens-ui/src/dock.tsx.
 *
 * Props: { cluster: string; sessions: DockSession[]; active: string | null }
 *
 * Emits (payloads match the React callback props exactly):
 *   (e: 'activate', id: string): void                    — tab clicked / make active
 *   (e: 'close', id: string): void                       — close a session
 *   (e: 'toggle-float', id: string, floating: boolean): void — pop out (true) / dock back (false)
 */
import { computed, onBeforeUnmount, onMounted, ref } from 'vue';

import type { DockSession } from '../dock';
import { sessionLabel } from '../dock';
import DockSessionView from './DockSession.vue';
import FloatingFrame from './FloatingFrame.vue';

const props = defineProps<{ cluster: string; sessions: DockSession[]; active: string | null }>();
const emit = defineEmits<{
  (e: 'activate', id: string): void;
  (e: 'close', id: string): void;
  (e: 'toggle-float', id: string, floating: boolean): void;
}>();

const height = ref(300);
const minimized = ref(false);

const dockBodyEl = ref<HTMLElement | null>(null);
const floatBodies = ref(new Map<string, HTMLElement>());

function setFloatBody(id: string, el: HTMLElement | null) {
  if (el) {
    floatBodies.value.set(id, el);
  } else {
    floatBodies.value.delete(id);
  }
}

const docked = computed(() => props.sessions.filter((s) => !s.floating));
const floating = computed(() => props.sessions.filter((s) => s.floating));

const activeId = computed(() => {
  const d = docked.value;
  return d.some((s) => s.id === props.active) ? props.active : (d[d.length - 1]?.id ?? null);
});

/** The current Teleport target for a session: its floating frame body, or the shared dock body. */
function hostFor(s: DockSession): HTMLElement | null {
  return s.floating ? (floatBodies.value.get(s.id) ?? null) : dockBodyEl.value;
}

// --- dock panel resize (drag the top edge) ---
const dragging = ref(false);

function startResize() {
  dragging.value = true;
  document.body.classList.add('row-resizing');
}

function onMove(e: PointerEvent) {
  if (!dragging.value) {
    return;
  }
  const h = window.innerHeight - e.clientY;
  // Leave room for the brand bar + some content above the dock.
  height.value = Math.min(Math.max(h, 120), window.innerHeight - 180);
}

function onUp() {
  dragging.value = false;
  document.body.classList.remove('row-resizing');
}

onMounted(() => {
  window.addEventListener('pointermove', onMove);
  window.addEventListener('pointerup', onUp);
});

onBeforeUnmount(() => {
  window.removeEventListener('pointermove', onMove);
  window.removeEventListener('pointerup', onUp);
});
</script>

<template>
  <FloatingFrame
    v-for="s in floating"
    :key="s.id"
    :session="s"
    @dock="emit('toggle-float', s.id, false)"
    @close="emit('close', s.id)"
    @body-el="(el) => setFloatBody(s.id, el)"
  />

  <div
    v-if="docked.length > 0"
    class="dock-panel"
    :class="{ minimized }"
    :style="minimized ? {} : { height: height + 'px' }"
  >
    <div class="dock-resize" title="Drag to resize" @pointerdown="startResize" />
    <div class="dock-tabs">
      <div
        v-for="s in docked"
        :key="s.id"
        class="dock-tab"
        :class="{ active: s.id === activeId }"
        @click="emit('activate', s.id)"
      >
        <i class="term-dot" :class="s.kind" />
        <span class="dock-tab-label">{{ sessionLabel(s.kind) }} · {{ s.namespace }}/{{ s.pod }}</span>
        <button class="dock-tab-btn" title="Open in a floating window" @click.stop="emit('toggle-float', s.id, true)">
          ⧉
        </button>
        <button class="dock-tab-close" title="Close" @click.stop="emit('close', s.id)">×</button>
      </div>
      <span class="dock-tab-spacer" />
      <button class="dock-min" :title="minimized ? 'Expand' : 'Minimize'" @click="minimized = !minimized">
        {{ minimized ? '▴' : '—' }}
      </button>
    </div>
    <div ref="dockBodyEl" class="dock-bodies" />
  </div>

  <Teleport v-for="s in sessions" :key="s.id" :to="hostFor(s)" :disabled="!hostFor(s)">
    <DockSessionView :cluster="cluster" :session="s" :visible="s.floating || s.id === activeId" />
  </Teleport>
</template>
