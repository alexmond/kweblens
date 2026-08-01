<script setup lang="ts">
// The slide-in resource Detail drawer: NDrawer + NDrawerContent, a NTabs bar
// (Overview / YAML / Events, plus Metrics for Pods) and Escape-to-close. Lazy-loads
// events when its tab is first shown.
//
// Emits (mirror the React callback props' payloads):
//   navigate     (kind: string, ns?: string)         — open another kind/object list
//   helm-release (namespace: string, name: string)   — open a Helm release's resources
//   auth-expired ()                                   — a YAML apply came back 401/403
//   require-auth ()                                   — a pane needs the login (Files)
//   close        ()                                   — close the drawer (X or Escape)
import { NDrawer, NDrawerContent, NTabPane, NTabs } from 'naive-ui';
import { shallowRef, computed, ref, watch } from 'vue';

import { api } from '../api';
import { useEscapeKey } from '../composables/useEscapeKey';
import { objName, objNs } from '../kube';
import { filesFeature } from '../podFiles';
import type { EventSummary, KubeObject } from '../types';
import EventsPane from './EventsPane.vue';
import MetricChart from './MetricChart.vue';
import NodePodsPane from './NodePodsPane.vue';
import Overview from './Overview.vue';
import PodFilesPane from './PodFilesPane.vue';
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
  (e: 'require-auth'): void;
  (e: 'open-object', obj: KubeObject): void;
  (e: 'close'): void;
}>();

type Tab = 'overview' | 'pods' | 'yaml' | 'events' | 'metrics' | 'files';

// Whether to offer the Files tab, decided ONCE when this drawer opens and deliberately not
// reactive. The pod file browser is off by default and there is no endpoint that reports
// it, so the first listing is what discovers it (see PodFilesPane). If that answer is
// "disabled", this drawer keeps the tab — the explanation the reader just asked for is on
// it — and every drawer opened afterwards omits it, so no tab sits there able only to 403.
const showFiles = filesFeature.state.value !== 'disabled';
const tab = ref<Tab>(props.initialEdit ? 'yaml' : 'overview');
const events = shallowRef<EventSummary[] | null>(null);
const eventsError = ref<string | null>(null);

const kind = computed(() => props.obj.kind ?? '');
const isNode = computed(() => kind.value === 'Node');
const name = computed(() => objName(props.obj));
const ns = computed(() => objNs(props.obj) ?? '');

// Expand-to-fill: the drawer is mounted into the content column (see `to` below), so 100%
// is exactly the area the table occupies — no header/footer/sidebar overlap either way.
// `width` tracks the user's own resizing so collapsing restores their chosen width.
const expanded = ref(false);
const width = ref(520);
const drawerWidth = computed<number | string>(() => (expanded.value ? '100%' : width.value));

// The parent mounts Detail via v-if; keep the drawer shown while mounted and route any
// close (the X, the mask, or Escape) to the `close` emit so the parent tears it down.
const show = ref(true);
// True while the YAML tab's pop-out editor is open. The drawer is non-modal and closes on
// any outside click / Escape — but a click inside the editor (a separate overlay) counts as
// "outside", which would close the drawer and unmount the editor. Suppress the drawer's
// close while editing; Escape then closes the editor (its own modal), not the drawer.
const yamlEditing = ref(false);
const onShow = (v: boolean) => {
  if (!v && !yamlEditing.value) {
    emit('close');
  }
};

useEscapeKey(() => {
  if (!yamlEditing.value) {
    emit('close');
  }
});

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
    :resizable="!expanded"
    :width="drawerWidth"
    :min-width="360"
    :max-width="1400"
    placement="right"
    to=".content-col"
    :show-mask="false"
    :trap-focus="false"
    :block-scroll="false"
    :aria-label="`${kind} ${name}`"
    @update:show="onShow"
    @update:width="(w) => (width = w)"
  >
    <NDrawerContent closable body-content-style="padding: 0 20px; display: flex; flex-direction: column;">
      <template #header>
        <div class="drawer-title">
          <span class="drawer-kind">{{ kind }}</span>
          <span class="drawer-name">{{ name }}</span>
          <button
            type="button"
            class="drawer-expand"
            :title="expanded ? 'Restore panel width' : 'Expand to fill'"
            :aria-label="expanded ? 'Restore panel width' : 'Expand to fill'"
            @click="expanded = !expanded"
          >
            {{ expanded ? '⤡' : '⤢' }}
          </button>
        </div>
      </template>

      <NTabs v-model:value="tab" type="line" size="small" pane-class="drawer-body">
        <NTabPane name="overview" tab="Overview" display-directive="if">
          <Overview
            :obj="obj"
            :cluster="cluster"
            :resource-id="resourceId"
            @navigate="(k, n) => emit('navigate', k, n)"
            @helm-release="(nsp, nm) => emit('helm-release', nsp, nm)"
          />
        </NTabPane>
        <NTabPane v-if="isNode" name="pods" tab="Pods" display-directive="if">
          <NodePodsPane :cluster="cluster" :node="name" @open="(o) => emit('open-object', o)" />
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
            @editing="(v) => (yamlEditing = v)"
          />
        </NTabPane>
        <NTabPane name="events" tab="Events" display-directive="if">
          <EventsPane :events="events" :error="eventsError" />
        </NTabPane>
        <NTabPane v-if="kind === 'Pod' && showFiles" name="files" tab="Files" display-directive="if">
          <PodFilesPane
            :cluster="cluster"
            :namespace="ns"
            :pod="name"
            :obj="obj"
            :authed="authed"
            @auth-required="emit('require-auth')"
          />
        </NTabPane>
        <NTabPane v-if="kind === 'Pod'" name="metrics" tab="Metrics" display-directive="if">
          <div class="charts vertical">
            <MetricChart :cluster="cluster" target="pod-cpu" :namespace="ns" :name="name" label="CPU (cores)" />
            <MetricChart :cluster="cluster" target="pod-mem" :namespace="ns" :name="name" label="Memory" />
          </div>
        </NTabPane>
        <NTabPane v-if="isNode" name="metrics" tab="Metrics" display-directive="if">
          <div class="charts vertical">
            <MetricChart :cluster="cluster" target="node-cpu" :name="name" label="CPU (cores)" />
            <MetricChart :cluster="cluster" target="node-mem" :name="name" label="Memory" />
            <MetricChart :cluster="cluster" target="node-disk" :name="name" label="Disk" />
          </div>
        </NTabPane>
      </NTabs>
    </NDrawerContent>
  </NDrawer>
</template>
