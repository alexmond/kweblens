<script setup lang="ts">
import { computed, ref, watch } from 'vue';

import HelmChartsPane from './HelmChartsPane.vue';
import HelmReleasesPane from './HelmReleasesPane.vue';
import HelmRepositoriesPane from './HelmRepositoriesPane.vue';
import HelmActionModal from './HelmActionModal.vue';
import HelmResourcesModal from './HelmResourcesModal.vue';
import HelmValuesModal from './HelmValuesModal.vue';
import HelmHistoryModal from './HelmHistoryModal.vue';
import type { HelmAction } from './helm-types';

// The Helm section root. Dispatches on `view` to one of three panes (charts / releases /
// repositories) and hosts the shared modals (action, resources, values, history).
//
// Props:
//   cluster        — active cluster id
//   view           — nav id/suffix: 'helm:charts' | 'helm:releases' | 'helm:repositories'
//                    (bare 'charts' | 'releases' | 'repositories' also accepted)
//   authed         — whether HTTP Basic creds are set (writes require it)
//   openResources  — deep-link from a resource's "Managed By: Helm": pre-open that release's
//                    resources modal. Mirrors the React `openResources` prop.
//
// Events emitted:
//   (e: 'navigate', kind: string, ns?: string): void   — open a resource kind/namespace in the shell
//   (e: 'require-auth'): void                            — an unauthenticated user attempted a write
//   (e: 'auth-expired'): void                            — a write returned 401/403
//   (e: 'resources-consumed'): void                      — the openResources deep-link has been applied
//                                                          (mirrors React `onResourcesConsumed`)
const props = defineProps<{
  cluster: string;
  view: string;
  authed: boolean;
  openResources?: { namespace: string; name: string } | null;
}>();

const emit = defineEmits<{
  (e: 'navigate', kind: string, ns?: string): void;
  (e: 'require-auth'): void;
  (e: 'auth-expired'): void;
  (e: 'resources-consumed'): void;
}>();

const action = ref<HelmAction | null>(null);
const resourcesFor = ref<{ namespace: string; name: string } | null>(null);
const valuesFor = ref<{ namespace: string; name: string } | null>(null);
const historyFor = ref<{ namespace: string; name: string } | null>(null);
const refreshKey = ref(0);

const pane = computed(() => {
  const v = props.view.startsWith('helm:') ? props.view.slice(5) : props.view;
  return v === 'charts' ? 'charts' : v === 'repositories' ? 'repositories' : 'releases';
});

const title = computed(() =>
  pane.value === 'charts' ? 'Helm · Charts' : pane.value === 'repositories' ? 'Helm · Repositories' : 'Helm · Releases',
);

const onAction = (a: HelmAction) => {
  if (props.authed) {
    action.value = a;
  } else {
    emit('require-auth');
  }
};

const onApplied = () => {
  action.value = null;
  refreshKey.value += 1;
};

const onResourcesOpen = (kind: string, ns: string) => {
  resourcesFor.value = null;
  emit('navigate', kind, ns);
};

// Deep-linked from a resource's "Managed By: Helm" — open that release's resources.
watch(
  () => props.openResources,
  (open) => {
    if (open) {
      resourcesFor.value = open;
      emit('resources-consumed');
    }
  },
  { immediate: true },
);
</script>

<template>
  <div class="overview">
    <div class="content-head">
      <h1>{{ title }}</h1>
    </div>

    <HelmChartsPane v-if="pane === 'charts'" :cluster="cluster" @action="onAction" />
    <HelmRepositoriesPane
      v-else-if="pane === 'repositories'"
      :authed="authed"
      @require-auth="emit('require-auth')"
      @auth-expired="emit('auth-expired')"
    />
    <HelmReleasesPane
      v-else
      :cluster="cluster"
      :authed="authed"
      :refresh-key="refreshKey"
      @action="onAction"
      @resources="(ns, name) => (resourcesFor = { namespace: ns, name })"
      @values="(ns, name) => (valuesFor = { namespace: ns, name })"
      @history="(ns, name) => (historyFor = { namespace: ns, name })"
      @require-auth="emit('require-auth')"
    />

    <HelmActionModal
      v-if="action"
      :cluster="cluster"
      :action="action"
      @close="action = null"
      @applied="onApplied"
      @auth-expired="emit('auth-expired')"
    />
    <HelmResourcesModal
      v-if="resourcesFor"
      :cluster="cluster"
      :namespace="resourcesFor.namespace"
      :name="resourcesFor.name"
      @close="resourcesFor = null"
      @open="onResourcesOpen"
    />
    <HelmValuesModal
      v-if="valuesFor"
      :cluster="cluster"
      :namespace="valuesFor.namespace"
      :name="valuesFor.name"
      @close="valuesFor = null"
    />
    <HelmHistoryModal
      v-if="historyFor"
      :cluster="cluster"
      :namespace="historyFor.namespace"
      :name="historyFor.name"
      @close="historyFor = null"
    />
  </div>
</template>
