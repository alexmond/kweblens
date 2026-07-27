<script setup lang="ts">
// The reusable events table (also used outside the drawer). Sortable columns, warning
// rows highlighted. Emits nothing — purely presentational.
import { useTableSort } from '../composables/useTableSort';
import { ageToSeconds } from '../kube';
import type { EventSummary } from '../types';
import SortTh from './SortTh.vue';

const props = defineProps<{ events: EventSummary[] | null; error: string | null }>();

const { sorted, sort, clickHeader } = useTableSort(
  () => props.events ?? [],
  'age',
  (e, k) => (k === 'age' ? ageToSeconds(e.age) : ((e[k as keyof EventSummary] as string) ?? '')),
);
</script>

<template>
  <div v-if="error" class="error">{{ error }}</div>
  <div v-else-if="events === null" class="empty">Loading…</div>
  <div v-else-if="events.length === 0" class="empty">No events.</div>
  <table v-else class="mini">
    <thead>
      <tr>
        <SortTh label="Type" col-key="type" :sort="sort" @sort="clickHeader" />
        <SortTh label="Reason" col-key="reason" :sort="sort" @sort="clickHeader" />
        <SortTh label="Message" col-key="message" :sort="sort" @sort="clickHeader" />
        <SortTh label="Age" col-key="age" :sort="sort" @sort="clickHeader" />
      </tr>
    </thead>
    <tbody>
      <tr v-for="(e, i) in sorted" :key="i" :class="e.type === 'Warning' ? 'warn' : ''">
        <td>{{ e.type }}</td>
        <td>{{ e.reason }}</td>
        <td>{{ e.message }}</td>
        <td>{{ e.age }}</td>
      </tr>
    </tbody>
  </table>
</template>
