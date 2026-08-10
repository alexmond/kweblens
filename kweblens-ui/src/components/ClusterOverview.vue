<script setup lang="ts">
// The cluster dashboard: node/namespace/warnings stat cards, API-server line, cluster
// CPU + memory metric charts, and a warnings table built from the cluster's events.
//
// Cards and warning rows navigate; the shell owns where to (this only names a kind). Note the
// split scope: warnings follow the namespace filter, while nodes and the cluster metric charts
// are cluster-scoped and CANNOT — so they say so instead of quietly showing unfiltered numbers
// beside filtered ones.
import DiagnosisPanel from './DiagnosisPanel.vue';
import { NDataTable } from 'naive-ui';
import type { DataTableColumns } from 'naive-ui';
import { shallowRef, computed, ref, watch } from 'vue';

import { api } from '../api';
import { failureNotice } from '../apiFailure';
import type { CheckState } from '../checkState';
import { checkedData, checkedDanger, checkedValue, uncheckedNote } from '../checkState';
import { warningsEmpty } from '../emptyState';
import { eventObjectKind } from '../kube';
import type { EventSummary, KubeObject } from '../types';
import EmptyState from './EmptyState.vue';
import MetricChart from './MetricChart.vue';
import StatCard from './StatCard.vue';
import { WARN_TABLE_MIN_WIDTH, warnColumns } from './warningsTable';

const props = defineProps<{
  cluster: string;
  name: string;
  masterUrl?: string;
  /** null when the namespace list could not be fetched — a count nobody answered is not 0. */
  namespaceCount: number | null;
  namespace?: string | null;
  /** Whether the shell can navigate to a kind — a row with nowhere to go must not look clickable. */
  knowsKind?: (kind: string) => boolean;
  /** Passed to the diagnosis panel: its Analyse trigger is a POST and needs the admin login. */
  authed?: boolean;
}>();

const emit = defineEmits<{
  (e: 'navigate', kind: string, namespace?: string): void;
  (e: 'require-auth'): void;
}>();

const nodes = shallowRef<CheckState<KubeObject[]>>({ status: 'checking' });
// Three states, not two. The failure branch used to write `[]` here, and the page then said
// "0 Warnings" and "No warnings." for a request that never came back — see checkState.ts.
const warnings = shallowRef<CheckState<EventSummary[]>>({ status: 'checking' });
const err = ref<string | null>(null);

let reqId = 0;
watch(
  () => [props.cluster, props.namespace] as const,
  ([cluster, namespace]) => {
    const my = ++reqId;
    nodes.value = { status: 'checking' };
    warnings.value = { status: 'checking' };
    err.value = null;
    api
      .objects(cluster, 'nodes')
      .then((n) => my === reqId && (nodes.value = { status: 'checked', data: n }))
      .catch((e) => {
        if (my === reqId) {
          // Both, not just the banner: a card still showing "…" after the request has
          // finished says "any moment now" about something that already failed.
          nodes.value = { status: 'unchecked', message: failureNotice(e) };
          err.value = failureNotice(e);
        }
      });
    api
      .events(cluster, namespace ?? undefined)
      .then(
        (ev) => my === reqId && (warnings.value = { status: 'checked', data: ev.filter((x) => x.type === 'Warning') }),
      )
      .catch((e) => my === reqId && (warnings.value = { status: 'unchecked', message: failureNotice(e) }));
  },
  { immediate: true },
);

/** The kind a warning row would open, or null when it has nowhere to go. */
const rowKind = (w: EventSummary): string | null => {
  const kind = eventObjectKind(w.object);
  return kind && (!props.knowsKind || props.knowsKind(kind)) ? kind : null;
};

const rowProps = (w: EventSummary) => {
  const kind = rowKind(w);
  if (!kind) {
    return {};
  }
  const go = () => emit('navigate', kind, w.namespace ?? undefined);
  return { class: 'row-link', style: { cursor: 'pointer' }, onClick: go };
};

const nodeReady = (o: KubeObject): boolean => {
  const conds = ((o.status as Record<string, unknown>)?.conditions as Record<string, unknown>[]) ?? [];
  const r = conds.find((c) => c.type === 'Ready');
  return r ? r.status === 'True' : false;
};
const nodeData = computed(() => checkedData(nodes.value));
const readyNodes = computed(() => (nodeData.value ?? []).filter(nodeReady).length);

// Column widths and the reasoning behind them live in warningsTable.ts (#257).
const columns = warnColumns() as DataTableColumns<EventSummary>;
// Capped for rendering, but the cap is REPORTED. Previously the stat card showed the true
// total while the table showed 30, so the page contradicted itself.
const WARNING_LIMIT = 30;
const warnData = computed(() => checkedData(warnings.value));
const warnRows = computed(() => (warnData.value ?? []).slice(0, WARNING_LIMIT));
const warningsTruncated = computed(() => (warnData.value?.length ?? 0) > WARNING_LIMIT);
const warningsUnchecked = computed(() => uncheckedNote(warnings.value, 'warnings'));
// Reads the CheckState directly rather than `warnData?.length`, because `warnData` is null
// for both "checking" and "unchecked" and this pane has to tell those apart (checkState.ts).
const warningsCopy = computed(() =>
  warningsEmpty({
    loading: warnings.value.status === 'checking',
    failed: warnings.value.status === 'unchecked',
    count: warnData.value?.length ?? 0,
    namespace: props.namespace ?? null,
  }),
);
</script>

<template>
  <div class="overview">
    <h1 class="ov-title">{{ name }}</h1>
    <div class="ov-scope-note">{{ namespace ? `Namespace: ${namespace}` : 'All namespaces' }}</div>
    <!-- Cards and charts share one band (#236). Three 260px cards left 1445px of a 2560px
         screen empty while the charts sat on their own row below; side by side, the charts
         take that space and the page is shorter. The band WRAPS rather than switching at a
         width: see `.ov-band` in styles.css for why this one is not a container query. -->
    <div class="ov-band">
      <div class="ov-band-cards">
        <div class="ov-cards">
          <StatCard
            :value="checkedValue(nodes)"
            :label="`Nodes${nodeData ? ` · ${readyNodes} ready` : ''}`"
            clickable
            @select="emit('navigate', 'Node')"
          />
          <StatCard
            :value="namespaceCount ?? '—'"
            label="Namespaces"
            clickable
            @select="emit('navigate', 'Namespace')"
          />
          <!-- `—` when the events call failed, never 0: the number is a claim about the
               cluster and we only make it when the cluster answered (checkState.ts). -->
          <StatCard :value="checkedValue(warnings)" label="Warnings" :danger="checkedDanger(warnings)" />
        </div>
        <!-- Nodes and the charts beside them are cluster-scoped. Saying so is the honest
             alternative to either ignoring the filter silently or pretending these can be
             narrowed. -->
        <div v-if="namespace" class="ov-scope-note">
          Nodes and cluster metrics are cluster-wide and ignore this filter.
        </div>
        <div v-if="masterUrl" class="ov-api">
          API server: <span class="mono">{{ masterUrl }}</span>
        </div>
      </div>
      <div class="charts">
        <MetricChart :cluster="cluster" target="cluster-cpu" label="Cluster CPU (cores)" />
        <MetricChart :cluster="cluster" target="cluster-mem" label="Cluster Memory" />
      </div>
    </div>
    <div v-if="err" class="error">{{ err }}</div>
    <!-- Diagnosis sits above Warnings: warnings are raw events, diagnosis is the reading
         of them plus what to do. Reason before evidence. -->
    <DiagnosisPanel
      :cluster="cluster"
      :namespace="namespace ?? null"
      :authed="authed"
      @require-auth="emit('require-auth')"
    />

    <section class="ov-sec">
      <h3>Warnings</h3>
      <div v-if="warningsTruncated" class="ov-truncated">
        Showing the {{ WARNING_LIMIT }} most recent of {{ warnData?.length }} warnings.
      </div>
      <!-- The failure gets its own line and suppresses both the count and the all-clear. -->
      <div v-if="warningsUnchecked" class="error">{{ warningsUnchecked }}</div>
      <EmptyState v-else-if="warningsCopy" :title="warningsCopy.title" :body="warningsCopy.body" variant="inline" />
      <!-- `table-layout="fixed"` is what makes the declared widths binding and hands the
           remainder to Message; `scroll-x` is the floor below which it scrolls instead. -->
      <NDataTable
        v-else
        class="warn-table"
        :columns="columns"
        :data="warnRows"
        :loading="warnings.status === 'checking'"
        :row-key="(w) => `${w.object}/${w.reason}/${w.age}`"
        :row-props="rowProps"
        :scroll-x="WARN_TABLE_MIN_WIDTH"
        table-layout="fixed"
        size="small"
      />
    </section>
  </div>
</template>
