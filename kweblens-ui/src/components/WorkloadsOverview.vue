<script setup lang="ts">
// The workloads dashboard: a StatCard grid over WORKLOAD_KINDS (total, plus ok / needing
// attention / suspended per kind) and a recent-events pane. Health predicates live in
// workloadHealth.ts so they can be tested without a DOM.
// Emits nothing — purely presentational (data is fetched internally per cluster).
import { shallowRef, computed, watch } from 'vue';

import { api } from '../api';
import type { EventSummary } from '../types';
import EventsPane from './EventsPane.vue';
import StatCard from './StatCard.vue';
import { WORKLOAD_KINDS, tally } from './workloadHealth';
import type { KindTally } from './workloadHealth';

const props = defineProps<{ cluster: string }>();

const counts = shallowRef<Record<string, KindTally>>({});
const events = shallowRef<EventSummary[] | null>(null);

let reqId = 0;
watch(
  () => props.cluster,
  (cluster) => {
    const my = ++reqId;
    counts.value = {};
    events.value = null;
    WORKLOAD_KINDS.forEach((k) => {
      api
        .objects(cluster, k.id)
        .then((objs) => {
          if (my !== reqId) {
            return;
          }
          counts.value = { ...counts.value, [k.id]: tally(objs, k) };
        })
        .catch(() => undefined);
    });
    api
      .events(cluster)
      .then((e) => my === reqId && (events.value = e))
      .catch(() => my === reqId && (events.value = []));
  },
  { immediate: true },
);

const cards = computed(() =>
  WORKLOAD_KINDS.map((k) => {
    const c = counts.value[k.id];
    if (!c) {
      return { id: k.id, value: '…', label: k.label, danger: false };
    }
    // Suspended is reported separately from attention: a suspended CronJob is a deliberate
    // choice, and colouring it red alongside real failures is how a health signal earns its
    // way into being ignored.
    const parts = [`${c.ok} ok`];
    if (c.attention > 0) {
      parts.push(`${c.attention} need attention`);
    }
    if (c.suspended > 0) {
      parts.push(`${c.suspended} suspended`);
    }
    return { id: k.id, value: c.total, label: `${k.label} · ${parts.join(' · ')}`, danger: c.attention > 0 };
  }),
);

// Events are capped for rendering, but the cap is REPORTED rather than silently applied —
// showing a subset without saying so is a factual claim about the cluster that is wrong.
const EVENT_LIMIT = 25;
const recentEvents = computed(() => (events.value ? events.value.slice(0, EVENT_LIMIT) : null));
const eventsTruncated = computed(() => (events.value?.length ?? 0) > EVENT_LIMIT);
</script>

<template>
  <div class="overview">
    <h1 class="ov-title">Workloads</h1>
    <div class="ov-cards">
      <StatCard v-for="c in cards" :key="c.id" :value="c.value" :label="c.label" :danger="c.danger" />
    </div>
    <section class="ov-sec">
      <h3>Recent Events</h3>
      <div v-if="eventsTruncated" class="ov-truncated">
        Showing the {{ EVENT_LIMIT }} most recent of {{ events?.length }} events.
      </div>
      <EventsPane :events="recentEvents" :error="null" />
    </section>
  </div>
</template>
