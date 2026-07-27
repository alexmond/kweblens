<script setup lang="ts">
import { computed } from 'vue';

import type { KubeObject } from '../types';

// One coloured square per container, by state (Freelens-style), with a hover tooltip.
const props = defineProps<{ obj: KubeObject }>();

function containerSquare(cs: Record<string, unknown>): { tone: string; title: string } {
  const name = String(cs.name ?? '');
  const state = (cs.state as Record<string, Record<string, unknown>>) ?? {};
  const ready = Boolean(cs.ready);
  const restarts = Number(cs.restartCount ?? 0);
  let tone = 'wait';
  const lines = [name];
  if (state.running) {
    tone = ready ? 'ok' : 'warn';
    lines.push(ready ? 'Running' : 'Running (not ready)');
    if (state.running.startedAt) {
      lines.push('Started At  ' + String(state.running.startedAt));
    }
  } else if (state.terminated) {
    const t = state.terminated;
    tone = t.exitCode === 0 ? 'done' : 'err';
    lines.push(`Terminated · ${String(t.reason ?? '')} (exit ${Number(t.exitCode ?? 0)})`);
  } else if (state.waiting) {
    const reason = String(state.waiting.reason ?? 'Waiting');
    tone = /crashloop|imagepull|errimage|error|invalid/i.test(reason) ? 'err' : 'wait';
    lines.push('Waiting · ' + reason);
  }
  if (restarts > 0) {
    lines.push('Restarts: ' + restarts);
  }
  return { tone, title: lines.join('\n') };
}

const squares = computed(() => {
  const obj = props.obj;
  const statuses = ((obj.status as Record<string, unknown>)?.containerStatuses as Record<string, unknown>[]) ?? [];
  const specContainers = ((obj.spec as Record<string, unknown>)?.containers as Record<string, unknown>[]) ?? [];
  const list = statuses.length > 0 ? statuses : specContainers.map((c) => ({ name: c.name, ready: false, state: {} }));
  return list.map((cs, i) => ({ key: String(cs.name ?? i), ...containerSquare(cs) }));
});
</script>

<template>
  <template v-if="squares.length === 0">—</template>
  <span v-else class="csquares">
    <span v-for="sq in squares" :key="sq.key" :class="'csq csq-' + sq.tone" :title="sq.title" />
  </span>
</template>
