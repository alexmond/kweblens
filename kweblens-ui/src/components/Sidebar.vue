<script setup lang="ts">
import { computed } from 'vue';

import { useNavCollapse } from '../composables/useNavCollapse';
import { initials } from '../kube';
import type { ClusterInfo, NavCategory, NavItem } from '../types';
import NavTree from './NavTree.vue';

// The cluster rail + the per-cluster nav tree sidebar.
//
// The rail is capped and ends in a button to the Clusters page. Two reasons, both found by
// the design review (docs/design/cluster-selection.md): `.rail` had no overflow, so past a
// viewport's worth of tiles the extras were simply unreachable; and `initials()` is
// `id.slice(0, 2)`, so on this box six clusters render as DE K3 KI KI KI KI — four of them
// indistinguishable. The cap keeps the rail to the few tiles it is actually good at, and
// the page is where you go when a two-letter tile cannot tell you what you need.
//
// Emits: set-cluster(id), select(item), toggle-favorite(id), show-clusters()
const props = defineProps<{
  clusters: ClusterInfo[];
  cluster: string | null;
  activeCluster: ClusterInfo | null;
  nav: NavCategory[];
  counts: Record<string, number>;
  favorites: string[];
  selected: NavItem | null;
  clustersPageOpen?: boolean;
}>();
const emit = defineEmits<{
  (e: 'set-cluster', id: string): void;
  (e: 'select', item: NavItem): void;
  (e: 'toggle-favorite', id: string): void;
  (e: 'show-clusters'): void;
}>();

/** How many tiles the rail shows before deferring to the page. */
const RAIL_LIMIT = 6;

// The active cluster is always shown even if it sorts past the cap — a rail that cannot
// show you where you are is worse than a short one.
const railClusters = computed(() => {
  const head = props.clusters.slice(0, RAIL_LIMIT);
  const active = props.clusters.find((c) => c.id === props.cluster);
  if (active && !head.some((c) => c.id === active.id)) {
    return [...head.slice(0, RAIL_LIMIT - 1), active];
  }
  return head;
});

const hiddenCount = computed(() => props.clusters.length - railClusters.value.length);

// Collapse (#237). The control moves with the state rather than being duplicated: while the
// tree is open it sits pinned at the bottom of the tree, labelled, where a collapse control
// is looked for (Radar's model); once the tree is gone there is nothing to pin it to, so it
// becomes a tile at the bottom of the rail — the only left-column chrome still on screen.
const { collapsed: navCollapsed, toggle: toggleNav, toggleLabel, toggleTitle } = useNavCollapse();
</script>

<template>
  <nav class="rail" aria-label="Clusters">
    <button
      v-for="c in railClusters"
      :key="c.id"
      :class="'tile' + (c.id === cluster && !clustersPageOpen ? ' active' : '')"
      :title="c.name"
      @click="emit('set-cluster', c.id)"
    >
      {{ initials(c.id) }}
    </button>
    <button
      :class="'tile tile-more' + (clustersPageOpen ? ' active' : '')"
      :title="hiddenCount > 0 ? `All clusters (${hiddenCount} more)` : 'All clusters'"
      aria-label="All clusters"
      @click="emit('show-clusters')"
    >
      {{ hiddenCount > 0 ? `+${hiddenCount}` : '···' }}
    </button>
    <button
      v-if="navCollapsed"
      class="tile tile-nav"
      :title="toggleTitle"
      :aria-label="toggleLabel"
      aria-expanded="false"
      @click="toggleNav"
    >
      »
    </button>
  </nav>
  <aside v-if="!navCollapsed" id="kw-nav" class="nav">
    <div class="nav-title">{{ activeCluster?.name ?? cluster ?? '—' }}</div>
    <div class="nav-scroll">
      <NavTree
        v-if="cluster"
        :categories="nav"
        :counts="counts"
        :favorites="favorites"
        :selected="selected?.id ?? null"
        @select="(i) => emit('select', i)"
        @toggle-favorite="(id) => emit('toggle-favorite', id)"
      />
    </div>
    <button
      class="nav-collapse"
      :title="toggleTitle"
      :aria-label="toggleLabel"
      aria-expanded="true"
      aria-controls="kw-nav"
      @click="toggleNav"
    >
      <span class="nav-collapse-chev">«</span>
      <span>Collapse</span>
    </button>
  </aside>
</template>
