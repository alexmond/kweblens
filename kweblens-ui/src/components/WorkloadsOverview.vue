<script setup lang="ts">
// The workloads dashboard: a StatCard grid over WORKLOAD_KINDS (total + ready per kind,
// danger-flagged when unhealthy) plus a recent-events pane.
// Emits nothing — purely presentational (data is fetched internally per cluster).
import { shallowRef, computed, watch } from 'vue';

import { api } from '../api';
import { objSpec, objStatus, toNum } from '../kube';
import type { EventSummary, KubeObject } from '../types';
import EventsPane from './EventsPane.vue';
import StatCard from './StatCard.vue';

const numOf = toNum;
// Scaled-to-zero counts as healthy (intentionally scaled down, not failing).
const replicasReady = (o: KubeObject): boolean => numOf(objStatus(o).readyReplicas) === numOf(objSpec(o).replicas);

// The Workloads overview cards. Each entry carries its own health predicate, so adding a
// workload kind is one entry here (no separate switch to keep in sync).
const WORKLOAD_KINDS: { id: string; label: string; healthy: (o: KubeObject) => boolean }[] = [
  { id: 'pods', label: 'Pods', healthy: (o) => objStatus(o).phase === 'Running' || objStatus(o).phase === 'Succeeded' },
  { id: 'deployments', label: 'Deployments', healthy: replicasReady },
  { id: 'statefulsets', label: 'Stateful Sets', healthy: replicasReady },
  {
    id: 'daemonsets',
    label: 'Daemon Sets',
    healthy: (o) => numOf(objStatus(o).numberReady) === numOf(objStatus(o).desiredNumberScheduled),
  },
  { id: 'replicasets', label: 'Replica Sets', healthy: replicasReady },
  { id: 'jobs', label: 'Jobs', healthy: (o) => numOf(objStatus(o).succeeded) > 0 },
  { id: 'cronjobs', label: 'Cron Jobs', healthy: () => true },
];

const props = defineProps<{ cluster: string }>();

const counts = shallowRef<Record<string, { total: number; ready: number }>>({});
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
          counts.value = {
            ...counts.value,
            [k.id]: { total: objs.length, ready: objs.filter((o) => k.healthy(o)).length },
          };
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
    return {
      id: k.id,
      value: c ? c.total : '…',
      label: k.label + (c ? ` · ${c.ready} ready` : ''),
      danger: c ? c.total - c.ready > 0 : false,
    };
  }),
);

const recentEvents = computed(() => (events.value ? events.value.slice(0, 25) : null));
</script>

<template>
  <div class="overview">
    <h1 class="ov-title">Workloads</h1>
    <div class="ov-cards">
      <StatCard v-for="c in cards" :key="c.id" :value="c.value" :label="c.label" :danger="c.danger" />
    </div>
    <section class="ov-sec">
      <h3>Recent Events</h3>
      <EventsPane :events="recentEvents" :error="null" />
    </section>
  </div>
</template>
