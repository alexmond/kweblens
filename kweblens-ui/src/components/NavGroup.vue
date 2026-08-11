<script setup lang="ts">
import { computed } from 'vue';

import { navLabelParts } from '../navLabel';
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

/**
 * Objects in a category, or nothing.
 *
 * There used to be a fallback here: with no counts available it returned the number of MENU
 * ENTRIES instead. That made the Gateway badge read "8" — its eight kinds — while Workloads'
 * "328" was a sum of objects, so the same badge meant two different things in the same
 * styling with nothing to say which. A plausible-looking wrong number is worse than none,
 * so a category with no counts now shows no badge.
 */
function categoryBadge(cat: NavCategory): string | undefined {
  const items = [...cat.items, ...(cat.subgroups ?? []).flatMap((g) => g.items)];
  const known = items.filter((i) => props.counts[i.id] !== undefined);
  return known.length > 0 ? String(known.reduce((sum, i) => sum + props.counts[i.id], 0)) : undefined;
}

// One rendered list is one collision set (#327): these items are the rows a reader compares
// with each other. A sub-group's kinds are a separate list and are split separately, which is
// what keeps `VerticalPodAutoscaler` competing only with its own API group.
const labelParts = computed(() => navLabelParts(props.cat.items.map((i) => i.label)));

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
      <li v-for="(it, i) in cat.items" :key="it.id">
        <NavLeaf
          :item="it"
          :parts="labelParts[i]"
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
