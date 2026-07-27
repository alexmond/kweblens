<script setup lang="ts">
import { computed, ref, watch } from 'vue';

import { allNavItems } from '../shell';
import type { NavCategory, NavItem } from '../types';
import NavGroup from './NavGroup.vue';
import NavLeaf from './NavLeaf.vue';

const props = defineProps<{
  categories: NavCategory[];
  counts: Record<string, number>;
  favorites: string[];
  selected: string | null;
}>();
const emit = defineEmits<{ (e: 'select', item: NavItem): void; (e: 'toggle-favorite', id: string): void }>();

const open = ref<Set<string>>(new Set());

const favItems = computed(() =>
  props.favorites
    .map((id) => allNavItems(props.categories).find((i) => i.id === id))
    .filter((i): i is NavItem => Boolean(i)),
);

// Open the category (and, for a nested custom-resource kind, its API-group sub-group) that
// holds the current selection.
watch(
  [() => props.categories, () => props.selected],
  () => {
    const labelsToOpen: string[] = [];
    props.categories.forEach((c) => {
      const directHit = c.items.some((i) => i.id === props.selected);
      const group = (c.subgroups ?? []).find((g) => g.items.some((i) => i.id === props.selected));
      if (directHit || group) {
        labelsToOpen.push(c.label);
      }
      if (group) {
        labelsToOpen.push(group.label);
      }
    });
    if (labelsToOpen.length > 0 && !labelsToOpen.every((l) => open.value.has(l))) {
      const next = new Set(open.value);
      labelsToOpen.forEach((l) => next.add(l));
      open.value = next;
    }
  },
  { immediate: true },
);

const toggle = (label: string, isOpen: boolean) => {
  const next = new Set(open.value);
  if (isOpen) {
    next.add(label);
  } else {
    next.delete(label);
  }
  open.value = next;
};

// A category with a single kind and no sub-groups is redundant as a group — render it flat.
const isTopLeaf = (cat: NavCategory): boolean => cat.items.length === 1 && (cat.subgroups?.length ?? 0) === 0;
</script>

<template>
  <div class="tree">
    <div v-if="favItems.length > 0" class="fav-section">
      <div class="fav-header">★ Favorites</div>
      <ul>
        <li v-for="it in favItems" :key="it.id">
          <NavLeaf
            :item="it"
            :selected="selected"
            :count="counts[it.id]"
            :favorited="true"
            @select="(i) => emit('select', i)"
            @toggle-favorite="(id) => emit('toggle-favorite', id)"
          />
        </li>
      </ul>
    </div>
    <template v-for="cat in categories" :key="cat.label">
      <div v-if="isTopLeaf(cat)" class="top-leaf">
        <NavLeaf
          :item="cat.items[0]"
          :selected="selected"
          :count="counts[cat.items[0].id]"
          :favorited="favorites.includes(cat.items[0].id)"
          @select="(i) => emit('select', i)"
          @toggle-favorite="(id) => emit('toggle-favorite', id)"
        />
      </div>
      <NavGroup
        v-else
        :cat="cat"
        :nested="false"
        :selected="selected"
        :counts="counts"
        :favorites="favorites"
        :open-set="open"
        @select="(i) => emit('select', i)"
        @toggle-favorite="(id) => emit('toggle-favorite', id)"
        @toggle="toggle"
      />
    </template>
  </div>
</template>
