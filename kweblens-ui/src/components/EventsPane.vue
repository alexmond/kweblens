<script setup lang="ts">
// The reusable events table (also used outside the drawer). NDataTable with sortable
// columns; Warning rows are highlighted (an amber badge on Type + a `warn` row class).
//
// Amber rather than red, and matching the Events LIST exactly: an event is read the same way
// wherever it appears, so a Warning must not look more severe in the drawer than in the list.
// See eventTypeTone for why a Warning is not an error.
// Emits nothing — purely presentational.
import { NDataTable } from 'naive-ui';
import type { DataTableColumns } from 'naive-ui';
import { computed, h } from 'vue';

import { eventTypeTone } from '../columns';
import { ageToSeconds } from '../kube';
import type { EventSummary } from '../types';
import StatusBadge from './StatusBadge.vue';

const props = defineProps<{ events: EventSummary[] | null; error: string | null }>();

const columns = computed<DataTableColumns<EventSummary>>(() => [
  {
    title: 'Type',
    key: 'type',
    sorter: (a, b) => (a.type ?? '').localeCompare(b.type ?? ''),
    render: (row) => h(StatusBadge, { text: String(row.type ?? ''), tone: eventTypeTone(row.type) }),
  },
  {
    title: 'Reason',
    key: 'reason',
    sorter: (a, b) => (a.reason ?? '').localeCompare(b.reason ?? ''),
  },
  {
    title: 'Message',
    key: 'message',
    sorter: (a, b) => (a.message ?? '').localeCompare(b.message ?? ''),
  },
  {
    title: 'Age',
    key: 'age',
    defaultSortOrder: 'ascend',
    sorter: (a, b) => ageToSeconds(a.age) - ageToSeconds(b.age),
    render: (row) => row.age,
  },
]);

const rowClassName = (row: EventSummary) => (row.type === 'Warning' ? 'warn' : '');
const data = computed(() => props.events ?? []);
</script>

<template>
  <div v-if="error" class="error">{{ error }}</div>
  <NDataTable
    v-else
    :columns="columns"
    :data="data"
    :loading="events === null"
    :row-class-name="rowClassName"
    size="small"
  >
    <template #empty>No events.</template>
  </NDataTable>
</template>
