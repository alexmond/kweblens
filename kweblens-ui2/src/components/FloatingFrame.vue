<script setup lang="ts">
/**
 * Floating (popped-out) terminal/log window chrome: draggable head, resize handle, and a
 * `.float-body` mount point. The body is intentionally empty — the parent DockArea teleports
 * the live DockSession into it (via the reported element), so the session survives dock↔float.
 *
 * Ports React `FloatingFrame` from kweblens-ui/src/dock.tsx.
 *
 * Emits:
 *   (e: 'dock'): void                                  — dock this session back into the panel
 *   (e: 'close'): void                                 — close the session
 *   (e: 'body-el', el: HTMLElement | null): void       — the `.float-body` element (null on unmount)
 */
import { onBeforeUnmount, onMounted, ref } from 'vue';

import type { DockSession } from '../dock';
import { sessionLabel } from '../dock';

const props = defineProps<{ session: DockSession }>();
const emit = defineEmits<{
  (e: 'dock'): void;
  (e: 'close'): void;
  (e: 'body-el', el: HTMLElement | null): void;
}>();

const rect = ref(props.session.rect ?? { x: 140, y: 140, w: 640, h: 340 });
const collapsed = ref(false);
const bodyRef = ref<HTMLDivElement | null>(null);

type Drag = { resize: boolean; sx: number; sy: number; ox: number; oy: number; ow: number; oh: number };
let drag: Drag | null = null;

function onMove(e: PointerEvent) {
  if (!drag) {
    return;
  }
  const dx = e.clientX - drag.sx;
  const dy = e.clientY - drag.sy;
  if (drag.resize) {
    rect.value = { ...rect.value, w: Math.max(280, drag.ow + dx), h: Math.max(160, drag.oh + dy) };
  } else {
    rect.value = { ...rect.value, x: Math.max(0, drag.ox + dx), y: Math.max(0, drag.oy + dy) };
  }
}

function onUp() {
  drag = null;
  document.body.classList.remove('dragging');
}

function start(resize: boolean, e: PointerEvent) {
  e.stopPropagation();
  drag = {
    resize,
    sx: e.clientX,
    sy: e.clientY,
    ox: rect.value.x,
    oy: rect.value.y,
    ow: rect.value.w,
    oh: rect.value.h,
  };
  document.body.classList.add('dragging');
}

function onHeadDown(e: PointerEvent) {
  if (!collapsed.value) {
    start(false, e);
  }
}

onMounted(() => {
  window.addEventListener('pointermove', onMove);
  window.addEventListener('pointerup', onUp);
  emit('body-el', bodyRef.value);
});

onBeforeUnmount(() => {
  window.removeEventListener('pointermove', onMove);
  window.removeEventListener('pointerup', onUp);
  emit('body-el', null);
});
</script>

<template>
  <div
    class="float-window"
    :class="{ collapsed }"
    :style="{
      left: rect.x + 'px',
      top: rect.y + 'px',
      width: rect.w + 'px',
      height: collapsed ? 'auto' : rect.h + 'px',
    }"
  >
    <div class="float-head" @pointerdown="onHeadDown">
      <span class="float-title">
        <i class="term-dot" :class="session.kind" /> {{ sessionLabel(session.kind) }} · {{ session.namespace }}/{{
          session.pod
        }}
      </span>
      <span class="float-actions">
        <button class="float-btn" :title="collapsed ? 'Expand' : 'Minimize'" @click="collapsed = !collapsed">
          {{ collapsed ? '▢' : '—' }}
        </button>
        <button class="float-btn" title="Dock back" @click="emit('dock')">⧉</button>
        <button class="float-btn" title="Close" @click="emit('close')">×</button>
      </span>
    </div>
    <div ref="bodyRef" class="float-body" />
    <div class="float-resize" title="Resize" @pointerdown="(e) => start(true, e)" />
  </div>
</template>
