<script setup lang="ts">
import type { NavItem } from '../types';

defineProps<{ item: NavItem; selected: string | null; count?: number; favorited: boolean }>();
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
    -->
    <span class="leaf-label" :title="item.label">{{ item.label }}</span>
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
