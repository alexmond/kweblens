<script setup lang="ts">
// The cluster dashboard: node/namespace/warnings stat cards, API-server line, cluster
// CPU + memory metric charts, and a warnings table built from the cluster's events.
//
// Cards and warning rows navigate; the shell owns where to (this only names a kind, and — for a
// state — the filter that selects it). Note the split scope: warnings follow the namespace
// filter, while nodes, namespaces and the cluster metric charts are cluster-scoped and CANNOT —
// so they say so instead of quietly showing unfiltered numbers beside filtered ones.
//
// The Nodes and Namespaces numbers come from the server's own check (`/overview/cluster`), not
// from counting a list here. That is what makes each state under a card clickable: the breakdown
// and the `status:` filter are the same verdict, computed once (StatusVocabulary, GH#337). The
// page used to compute "N ready" in this file from a second predicate — the exact shape of
// discrepancy GH#336 was opened about.
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
import type { EventSummary, KindHealth } from '../types';
import EmptyState from './EmptyState.vue';
import ErrorNotice from './ErrorNotice.vue';
import MetricChart from './MetricChart.vue';
import StatCard from './StatCard.vue';
import { clusterCards } from './clusterOverviewCards';
import { WARN_TABLE_MIN_WIDTH, warnColumns } from './warningsTable';

const props = defineProps<{
  cluster: string;
  name: string;
  masterUrl?: string;
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

const health = shallowRef<CheckState<KindHealth[]>>({ status: 'checking' });
// Three states, not two. The failure branch used to write `[]` here, and the page then said
// "0 Warnings" and "No warnings." for a request that never came back — see checkState.ts.
const warnings = shallowRef<CheckState<EventSummary[]>>({ status: 'checking' });
const err = ref<string | null>(null);

// Two independent reads, so two independent retries. Both are GETs — a Retry on either costs
// one more request and changes nothing — but they must not share a button: the health check and
// the events call fail for different reasons, and re-running the one that worked in order to
// re-try the one that did not would blank a card that is currently right.
let healthReq = 0;
const loadHealth = () => {
  const my = ++healthReq;
  health.value = { status: 'checking' };
  err.value = null;
  api
    // No namespace argument, and not because it is optional: Nodes and Namespaces are
    // cluster-scoped, so narrowing them is a question the API does not answer (#313). The
    // server ignores it too; passing it here would be this file claiming otherwise.
    .overview(props.cluster, 'cluster')
    .then((h) => my === healthReq && (health.value = { status: 'checked', data: h }))
    .catch((e) => {
      if (my === healthReq) {
        // Both, not just the banner: a card still showing "…" after the request has
        // finished says "any moment now" about something that already failed.
        health.value = { status: 'unchecked', message: failureNotice(e) };
        err.value = failureNotice(e);
      }
    });
};

let warnReq = 0;
const loadWarnings = () => {
  const my = ++warnReq;
  warnings.value = { status: 'checking' };
  api
    .events(props.cluster, props.namespace ?? undefined)
    .then(
      (ev) => my === warnReq && (warnings.value = { status: 'checked', data: ev.filter((x) => x.type === 'Warning') }),
    )
    .catch((e) => my === warnReq && (warnings.value = { status: 'unchecked', message: failureNotice(e) }));
};

// Deliberately different dependencies. Warnings are events and DO follow the namespace filter;
// the health check is over cluster-scoped kinds and does not, so re-running it on a namespace
// change would be a request whose answer cannot differ — and a card blinking back to "…" would
// suggest the filter had narrowed something.
watch(() => props.cluster, loadHealth, { immediate: true });
watch(() => [props.cluster, props.namespace] as const, loadWarnings, { immediate: true });

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

/** The Nodes and Namespaces cards, states and all — the rules live in a .ts and are tested. */
const cards = computed(() => clusterCards(health.value));

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
          <!-- Cards, states and all, from the server's check — see clusterOverviewCards.ts for
               what a card shows when the check did not answer. -->
          <StatCard
            v-for="c in cards"
            :key="c.kind"
            :value="c.value"
            :label="c.label"
            :states="c.states"
            clickable
            @select="emit('navigate', c.kind)"
          />
          <!-- `—` when the events call failed, never 0: the number is a claim about the
               cluster and we only make it when the cluster answered (checkState.ts).
               No breakdown and no state links: an event's Warning/Normal is a field on a
               report about another object, not a verdict on the event, and the warnings it
               counts are the table below rather than a list this could open (GH#339). -->
          <StatCard :value="checkedValue(warnings)" label="Warnings" :danger="checkedDanger(warnings)" />
        </div>
        <!-- Nodes, Namespaces and the charts beside them are cluster-scoped. Saying so is the
             honest alternative to either ignoring the filter silently or pretending these can
             be narrowed. -->
        <div v-if="namespace" class="ov-scope-note">
          Nodes, namespaces and cluster metrics are cluster-wide and ignore this filter.
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
    <ErrorNotice v-if="err" :message="err" :retrying="health.status === 'checking'" @retry="loadHealth" />
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
      <!-- The failure gets its own line and suppresses both the count and the all-clear. The
           wording stays `uncheckedNote`'s — "unknown, not clear" — because that is the claim;
           what is new is that the reader can act on it without reloading the whole page. -->
      <ErrorNotice
        v-if="warningsUnchecked"
        :message="warningsUnchecked"
        :retrying="warnings.status === 'checking'"
        @retry="loadWarnings"
      />
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
