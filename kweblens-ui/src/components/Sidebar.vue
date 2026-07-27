<script setup lang="ts">
import { initials } from '../kube';
import type { ClusterInfo, NavCategory, NavItem } from '../types';
import NavTree from './NavTree.vue';

// The cluster rail + the per-cluster nav tree sidebar.
// Emits: set-cluster(id), select(item), toggle-favorite(id)
defineProps<{
  clusters: ClusterInfo[];
  cluster: string | null;
  activeCluster: ClusterInfo | null;
  nav: NavCategory[];
  counts: Record<string, number>;
  favorites: string[];
  selected: NavItem | null;
}>();
const emit = defineEmits<{
  (e: 'set-cluster', id: string): void;
  (e: 'select', item: NavItem): void;
  (e: 'toggle-favorite', id: string): void;
}>();
</script>

<template>
  <nav class="rail" aria-label="Clusters">
    <button
      v-for="c in clusters"
      :key="c.id"
      :class="'tile' + (c.id === cluster ? ' active' : '')"
      :title="c.name"
      @click="emit('set-cluster', c.id)"
    >
      {{ initials(c.id) }}
    </button>
  </nav>
  <aside class="nav">
    <div class="nav-title">{{ activeCluster?.name ?? cluster ?? '—' }}</div>
    <NavTree
      v-if="cluster"
      :categories="nav"
      :counts="counts"
      :favorites="favorites"
      :selected="selected?.id ?? null"
      @select="(i) => emit('select', i)"
      @toggle-favorite="(id) => emit('toggle-favorite', id)"
    />
  </aside>
</template>
