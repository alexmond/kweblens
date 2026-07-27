<script setup lang="ts">
import type { NavItem } from '../types';

defineProps<{ item: NavItem; selected: string | null; count?: number; favorited: boolean }>();
const emit = defineEmits<{ (e: 'select', item: NavItem): void; (e: 'toggle-favorite', id: string): void }>();
</script>

<template>
  <button :class="'leaf' + (item.id === selected ? ' active' : '')" @click="emit('select', item)">
    <span class="leaf-label">{{ item.label }}</span>
    <span v-if="count !== undefined" class="nav-badge">{{ count }}</span>
    <span
      :class="'fav-star' + (favorited ? ' on' : '')"
      :title="favorited ? 'Unpin' : 'Pin to Favorites'"
      @click.stop="emit('toggle-favorite', item.id)"
    >
      {{ favorited ? '★' : '☆' }}
    </span>
  </button>
</template>
