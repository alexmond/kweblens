<script setup lang="ts">
import { computed, ref, watch } from 'vue';

import { navLabelParts } from '../navLabel';
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

// A collision set of its own (#327): pinning both halves of a near-identical pair is exactly
// when they end up adjacent.
const favParts = computed(() => navLabelParts(favItems.value.map((i) => i.label)));
</script>

<template>
  <div class="tree">
    <div v-if="favItems.length > 0" class="fav-section">
      <div class="fav-header">★ Favorites</div>
      <ul>
        <li v-for="(it, i) in favItems" :key="it.id">
          <NavLeaf
            :item="it"
            :parts="favParts[i]"
            :selected="selected"
            :count="counts[it.id]"
            :favorited="true"
            @select="(i) => emit('select', i)"
            @toggle-favorite="(id) => emit('toggle-favorite', id)"
          />
        </li>
      </ul>
    </div>
    <!-- Every category renders as a group, including one holding a single kind. A category
         with one kind used to be flattened into a bare row, and #428 is what showed the cost:
         on a cluster with no VPA CRD the Autoscaling category is HPA alone, so the word
         "Autoscaling" left the menu entirely on exactly the clusters the ticket was about —
         an operator scanning headings for it finds nothing. The heading is what a category
         IS. (The flat rule's own comment named "Nodes, Namespaces, Events", which have been
         one Cluster category for many releases; it was a leftover from a nav shape that no
         longer exists.) -->
    <template v-for="cat in categories" :key="cat.label">
      <NavGroup
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
