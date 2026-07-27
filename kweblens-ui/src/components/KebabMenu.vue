<script setup lang="ts">
import { computed, ref } from 'vue';

import { useMenuDismiss } from '../composables/useMenuDismiss';
import type { KebabItem } from './helm-types';

// A generic kebab (⋮) actions menu for the non-resource tables (Helm charts/releases/repos).
// The menu is teleported to <body> with a fixed anchor so the table's overflow container
// never clips it — mirrors the React KebabMenu's portal/anchor behaviour.
const props = defineProps<{ items: KebabItem[] }>();

const open = ref(false);
const anchor = ref<{ left: number; top: number; up: boolean } | null>(null);
const btnRef = ref<HTMLButtonElement | null>(null);
const menuRef = ref<HTMLElement | null>(null);

useMenuDismiss(open, () => (open.value = false), btnRef, menuRef);

const toggle = () => {
  if (open.value) {
    open.value = false;
    return;
  }
  const r = btnRef.value?.getBoundingClientRect();
  if (r) {
    const estHeight = 20 + props.items.length * 30;
    const up = r.bottom + estHeight > window.innerHeight;
    anchor.value = { left: r.right, top: up ? r.top : r.bottom, up };
  }
  open.value = true;
};

const choose = (it: KebabItem) => {
  open.value = false;
  it.onClick();
};

const menuStyle = computed(() => {
  const a = anchor.value;
  if (!a) {
    return {};
  }
  return {
    position: 'fixed' as const,
    left: `${a.left}px`,
    top: `${a.top}px`,
    transform: a.up ? 'translate(-100%, -100%)' : 'translate(-100%, 0)',
  };
});
</script>

<template>
  <div class="rowmenu" @click.stop>
    <button ref="btnRef" class="kebab" title="Actions" @click.stop="toggle">⋮</button>
    <Teleport to="body">
      <div v-if="open && anchor" ref="menuRef" class="menu menu-portal" :style="menuStyle" @click.stop>
        <button
          v-for="it in items"
          :key="it.label"
          :class="'menu-item' + (it.danger ? ' danger' : '')"
          :disabled="it.disabled"
          @click.stop="choose(it)"
        >
          {{ it.label }}
        </button>
      </div>
    </Teleport>
  </div>
</template>
