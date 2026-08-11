<script setup lang="ts">
import type { NavLabelParts } from '../navLabel';
import type { NavItem } from '../types';

defineProps<{
  item: NavItem;
  selected: string | null;
  count?: number;
  favorited: boolean;
  parts: NavLabelParts;
}>();
const emit = defineEmits<{ (e: 'select', item: NavItem): void; (e: 'toggle-favorite', id: string): void }>();
</script>

<template>
  <!--
    The star sits BEFORE the badge deliberately (#242). With the badge last, it ends at the
    row's padding edge — the same place `.group > summary` ends — so a flat top-level kind's
    count lines up with the category counts instead of being pushed ~23px inboard by the star
    and its gap. The star keeps its own box in the flow (it is only hidden by opacity), so
    nothing shifts on hover.
  -->
  <button :class="'leaf' + (item.id === selected ? ' active' : '')" @click="emit('select', item)">
    <!--
      `title` on the label, not the button: CSS truncates the label with an ellipsis, and the
      pair that made this necessary (#281) — Validating Admission Policies and Validating
      Admission Policy Bindings — collapse to the same visible "Validating Admissio…", so the
      only way to tell them apart was to click one and read the heading. Applied to every leaf
      rather than the ambiguous pair, because which labels truncate depends on the nav width
      and on whatever CRDs the cluster has, neither of which is known here.

      The hover text was never enough on its own (#327): a reader has to already suspect there
      is something to disambiguate before hovering, and two rows reading "VerticalPodAuto…" give
      no such hint. So the label is also SPLIT — `parts.head` is elidable, `parts.tail` is the
      part that tells this leaf from its siblings (see navLabelParts). Two flex items rather
      than one string, so the browser decides how much head to drop and the tail is never the
      thing that goes. When the whole label fits, the halves sit flush and look unsplit.
    -->
    <span class="leaf-label" :title="item.label"
      ><span v-if="parts.head" class="leaf-head">{{ parts.head }}</span
      ><span class="leaf-tail">{{ parts.tail }}</span></span
    >
    <span
      :class="'fav-star' + (favorited ? ' on' : '')"
      :title="favorited ? 'Unpin' : 'Pin to Favorites'"
      @click.stop="emit('toggle-favorite', item.id)"
    >
      {{ favorited ? '★' : '☆' }}
    </span>
    <span v-if="count !== undefined" class="nav-badge">{{ count }}</span>
  </button>
</template>
