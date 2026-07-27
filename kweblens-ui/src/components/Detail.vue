<script setup lang="ts">
// The slide-in resource Detail drawer: NDrawer + NDrawerContent, a NTabs bar
// (Overview / YAML / Events, plus Metrics for Pods) and Escape-to-close. Lazy-loads
// events when its tab is first shown.
//
// Emits (mirror the React callback props' payloads):
//   navigate     (kind: string, ns?: string)         — open another kind/object list
//   helm-release (namespace: string, name: string)   — open a Helm release's resources
//   auth-expired ()                                   — a YAML apply came back 401/403
//   close        ()                                   — close the drawer (X or Escape)
import { NDrawer, NDrawerContent, NTabPane, NTabs } from 'naive-ui';
import { computed, ref, watch } from 'vue';

import { api } from '../api';
import { useEscapeKey } from '../composables/useEscapeKey';
import { objName, objNs } from '../kube';
import type { EventSummary, KubeObject } from '../types';
import EventsPane from './EventsPane.vue';
import MetricChart from './MetricChart.vue';
import Overview from './Overview.vue';
import YamlTab from './YamlTab.vue';

const props = defineProps<{
  cluster: string;
  resourceId: string;
  obj: KubeObject;
  initialEdit: boolean;
  authed: boolean;
}>();
const emit = defineEmits<{
  (e: 'navigate', kind: string, ns?: string): void;
  (e: 'helm-release', namespace: string, name: string): void;
  (e: 'auth-expired'): void;
  (e: 'close'): void;
}>();

type Tab = 'overview' | 'yaml' | 'events' | 'metrics';
const tab = ref<Tab>(props.initialEdit ? 'yaml' : 'overview');
const events = ref<EventSummary[] | null>(null);
const eventsError = ref<string | null>(null);

const kind = computed(() => props.obj.kind ?? '');
const name = computed(() => objName(props.obj));
const ns = computed(() => objNs(props.obj) ?? '');

// The parent mounts Detail via v-if; keep the drawer shown while mounted and route any
// close (the X, the mask, or Escape) to the `close` emit so the parent tears it down.
const show = ref(true);
const onShow = (v: boolean) => {
  if (!v) {
    emit('close');
  }
};

useEscapeKey(() => emit('close'));

watch(
  () => tab.value,
  (_now, _prev, onCleanup) => {
    if (tab.value !== 'events' || events.value !== null || eventsError.value !== null) {
      return;
    }
    let cancelled = false;
    onCleanup(() => (cancelled = true));
    api
      .objectEvents(props.cluster, kind.value, name.value, ns.value || undefined)
      .then((e) => !cancelled && (events.value = e))
      .catch((e) => !cancelled && (eventsError.value = String(e)));
  },
  { immediate: true },
);
</script>

<template>
  <NDrawer
    :show="show"
    :width="500"
    placement="right"
    :show-mask="false"
    :trap-focus="false"
    :block-scroll="false"
    :aria-label="`${kind} ${name}`"
    @update:show="onShow"
  >
    <NDrawerContent closable body-content-style="padding: 0; display: flex; flex-direction: column;">
      <template #header>
        <div class="drawer-title">
          <span class="drawer-kind">{{ kind }}</span>
          <span class="drawer-name">{{ name }}</span>
        </div>
      </template>

      <NTabs v-model:value="tab" type="line" size="small" pane-class="drawer-body">
        <NTabPane name="overview" tab="Overview" display-directive="if">
          <Overview
            :obj="obj"
            @navigate="(k, n) => emit('navigate', k, n)"
            @helm-release="(nsp, nm) => emit('helm-release', nsp, nm)"
          />
        </NTabPane>
        <NTabPane name="yaml" tab="YAML" display-directive="if">
          <YamlTab
            :cluster="cluster"
            :resource-id="resourceId"
            :name="name"
            :ns="ns"
            :initial-edit="initialEdit"
            :authed="authed"
            @auth-expired="emit('auth-expired')"
          />
        </NTabPane>
        <NTabPane name="events" tab="Events" display-directive="if">
          <EventsPane :events="events" :error="eventsError" />
        </NTabPane>
        <NTabPane v-if="kind === 'Pod'" name="metrics" tab="Metrics" display-directive="if">
          <div class="charts vertical">
            <MetricChart :cluster="cluster" target="pod-cpu" :namespace="ns" :name="name" label="CPU (cores)" />
            <MetricChart :cluster="cluster" target="pod-mem" :namespace="ns" :name="name" label="Memory" />
          </div>
        </NTabPane>
      </NTabs>
    </NDrawerContent>
  </NDrawer>
</template>
