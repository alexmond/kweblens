<script setup lang="ts">
import { computed } from 'vue';

import { useKebab } from '../composables/useKebab';
import type { RowAction } from '../rowActions';
import { ROW_ACTIONS } from '../rowActions';

// Per-row kebab (⋮) actions menu (Freelens-style), kind-aware: it renders whichever entries of
// ROW_ACTIONS apply to this kind — no per-kind markup lives here.
// Emits: ('action', action: RowAction, container?: string)
const props = defineProps<{ kind: string; suspended: boolean; containers: string[] }>();
const emit = defineEmits<{ (e: 'action', action: RowAction, container?: string): void }>();

const { open, anchor, btnRef, menuRef, toggle, menuStyle } = useKebab();

const applicable = computed(() =>
  ROW_ACTIONS.filter((a) => a.applies({ kind: props.kind, suspended: props.suspended })),
);
const main = computed(() => applicable.value.filter((a) => a.section === 'main'));
const lifecycle = computed(() => applicable.value.filter((a) => a.section === 'lifecycle'));

const run = (action: RowAction, container?: string) => {
  open.value = false;
  emit('action', action, container);
};
</script>

<template>
  <div class="rowmenu" @click.stop>
    <button ref="btnRef" class="kebab" title="Actions" @click.stop="toggle(280)">⋮</button>
    <Teleport to="body">
      <div v-if="open && anchor" ref="menuRef" class="menu menu-portal" :style="menuStyle(anchor)" @click.stop>
        <template v-for="a in main" :key="a.id">
          <div v-if="a.containerScoped && containers.length > 1" class="menu-item has-sub">
            <span>{{ a.label }}</span>
            <span class="sub-arrow">›</span>
            <div class="submenu">
              <button v-for="c in containers" :key="c" class="menu-item" @click.stop="run(a.id, c)">{{ c }}</button>
            </div>
          </div>
          <button v-else :class="'menu-item' + (a.danger ? ' danger' : '')" @click.stop="run(a.id)">
            {{ a.label }}
          </button>
        </template>
        <div v-if="main.length > 0 && lifecycle.length > 0" class="menu-sep" />
        <button
          v-for="a in lifecycle"
          :key="a.id"
          :class="'menu-item' + (a.danger ? ' danger' : '')"
          @click.stop="run(a.id)"
        >
          {{ a.label }}
        </button>
      </div>
    </Teleport>
  </div>
</template>
