<script setup lang="ts">
import { computed } from 'vue';

import type { NavCategory, NavItem } from '../types';
// Recursive component: renders nested sub-groups (Custom Resources → API groups).
import NavGroup from './NavGroup.vue';
import NavLeaf from './NavLeaf.vue';

const props = defineProps<{
  cat: NavCategory;
  nested: boolean;
  selected: string | null;
  counts: Record<string, number>;
  favorites: string[];
  openSet: Set<string>;
}>();
const emit = defineEmits<{
  (e: 'select', item: NavItem): void;
  (e: 'toggle-favorite', id: string): void;
  (e: 'toggle', label: string, isOpen: boolean): void;
}>();

function categoryBadge(cat: NavCategory): string {
  const known = cat.items.filter((i) => props.counts[i.id] !== undefined);
  if (known.length > 0) {
    return String(known.reduce((sum, i) => sum + props.counts[i.id], 0));
  }
  const nested = (cat.subgroups ?? []).reduce((sum, g) => sum + g.items.length, 0);
  return String(cat.items.length + nested);
}

const holdsSelected = computed(
  () =>
    props.cat.items.some((i) => i.id === props.selected) ||
    (props.cat.subgroups ?? []).some((g) => g.items.some((i) => i.id === props.selected)),
);
const onToggle = (e: Event) => emit('toggle', props.cat.label, (e.target as HTMLDetailsElement).open);
</script>

<template>
  <details
    :class="'group' + (nested ? ' subgroup' : '') + (holdsSelected ? ' holds-selected' : '')"
    :open="openSet.has(cat.label)"
    @toggle="onToggle"
  >
    <summary>
      <span class="chev">▸</span>
      <span class="cat-label">{{ cat.label }}</span>
      <span class="nav-badge">{{ categoryBadge(cat) }}</span>
    </summary>
    <ul v-if="cat.items.length > 0">
      <li v-for="it in cat.items" :key="it.id">
        <NavLeaf
          :item="it"
          :selected="selected"
          :count="counts[it.id]"
          :favorited="favorites.includes(it.id)"
          @select="(i) => emit('select', i)"
          @toggle-favorite="(id) => emit('toggle-favorite', id)"
        />
      </li>
    </ul>
    <NavGroup
      v-for="g in cat.subgroups ?? []"
      :key="g.label"
      :cat="g"
      :nested="true"
      :selected="selected"
      :counts="counts"
      :favorites="favorites"
      :open-set="openSet"
      @select="(i) => emit('select', i)"
      @toggle-favorite="(id) => emit('toggle-favorite', id)"
      @toggle="(l, o) => emit('toggle', l, o)"
    />
  </details>
</template>
