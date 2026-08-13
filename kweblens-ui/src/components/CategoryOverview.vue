<script setup lang="ts">
// One dashboard for every category — Workloads, Network, Storage, Config.
//
// Leads with the totals, then names the objects that need attention.
//
// The order was the other way round on the reasoning that a count is only interesting when it is
// unexpected. In use it reads wrong: the cards are the orientation you want before a list of
// individual problems, and putting a detail table above its own summary made every overview
// disagree with the Cluster one. The naming still matters — "one of your 52 Services is broken"
// without saying which just moves the hunt one step later — it simply comes second.
//
// The checks are computed SERVER-SIDE (/overview/<category>) and they differ completely per
// category — a workload verdict, a Service-to-Endpoints join, a PVC phase, a reverse reference
// scan — but they all answer the same shape of question, so one component renders all of them and
// the same summaries can serve a future TUI and the agent.
import { shallowRef, computed, watch } from 'vue';

import { api } from '../api';
import { failureNotice } from '../apiFailure';
import type { CheckState } from '../checkState';
import { checkedData, uncheckedNote } from '../checkState';
import { useAsyncData } from '../composables/useAsyncData';
import { attentionEmpty } from '../emptyState';
import type { EventSummary, KindHealth } from '../types';
import EmptyState from './EmptyState.vue';
import ErrorNotice from './ErrorNotice.vue';
import EventsPane from './EventsPane.vue';
import StatCard from './StatCard.vue';
import { OVERVIEW_CATEGORIES } from './overviewCategories';

const props = defineProps<{ cluster: string; category: string; namespace?: string | null }>();

// Emitted with the kind (and namespace, for a named object) the user asked to see. The shell owns
// navigation; the overview only says where it wants to go.
//
// `navigate-state` is the same request narrowed: the kind, plus the filter query that selects
// exactly the objects the card counted under that state (#338). The QUERY travels, not the state
// label — the card already asked the filter grammar how to write it (`objectFilter.statusQuery`),
// and re-deriving it in the shell would be a second spelling of the same rule, free to drift from
// the one the parser reads.
const emit = defineEmits<{
  (e: 'navigate', kind: string, namespace?: string): void;
  (e: 'navigate-state', kind: string, query: string): void;
}>();

const copy = computed(() => OVERVIEW_CATEGORIES[props.category]);
/** Events are about workloads; on a storage or config page they would be noise. */
const showEvents = computed(() => props.category === 'workloads');

// Both requests here are reads, so both failures may offer to run themselves again. They are
// retried SEPARATELY because they fail separately: the overview check going down does not
// make the events list stale, and one button that re-ran both would re-fetch the half that
// worked in order to re-try the half that did not.
const {
  data: health,
  loading: healthLoading,
  error,
  reload: reloadHealth,
} = useAsyncData<KindHealth[]>(
  () => [props.cluster, props.category, props.namespace],
  () => api.overview(props.cluster, props.category, props.namespace ?? undefined),
);

// The events call gets the same three states the health check has always had. Its failure
// used to be written as `[]` and then handed to EventsPane as `:error="null"` — the one
// component built to tell those apart, told there was nothing to tell.
const events = shallowRef<CheckState<EventSummary[]>>({ status: 'checking' });
let eventReq = 0;
const loadEvents = () => {
  if (!showEvents.value) {
    return;
  }
  const my = ++eventReq;
  events.value = { status: 'checking' };
  api
    .events(props.cluster, props.namespace ?? undefined)
    .then((e) => my === eventReq && (events.value = { status: 'checked', data: e }))
    .catch((e) => my === eventReq && (events.value = { status: 'unchecked', message: failureNotice(e) }));
};
watch(
  // Re-fetch on namespace as well as cluster: the filter is in the header the whole time this
  // page is open, so ignoring it here made it look broken rather than inapplicable.
  () => [props.cluster, props.category, props.namespace] as const,
  loadEvents,
  { immediate: true },
);

// No danger wash on a category card any more: the bar and the state list already say what
// is wrong and in what proportion, so tinting the whole card as well painted most of a row
// red without adding information. The Cluster overview's Warnings card keeps it — it has no
// breakdown to carry the signal.
const cards = computed(() =>
  (health.value ?? []).map((k) => {
    if (k.error) {
      // "Could not check" must never render as a healthy zero. Still clickable: the list page is
      // where the actual error is reported, so it is the useful next step.
      return { id: k.id, kind: k.kind, value: '—', label: `${k.label} · unavailable`, states: [] };
    }
    // The label is now just the kind: the breakdown that used to be crammed into it is a
    // list of its own, which is legible past two states where a run-on line was not.
    return {
      id: k.id,
      kind: k.kind,
      value: k.total,
      label: k.label,
      states: k.states ?? [],
    };
  }),
);

/** Everything needing attention, across kinds — the thing the page should open with. */
const attention = computed(() => (health.value ?? []).flatMap((k) => k.needsAttention));
const attentionTruncated = computed(() => (health.value ?? []).some((k) => k.truncated));
const unavailable = computed(() => (health.value ?? []).filter((k) => k.error));

/**
 * What an empty attention table means here — and in particular whether the category's own
 * all-clear may be said at all. It may not when some kind's check errored: an overview
 * assembles one verdict out of several per-kind checks, so "Everything is healthy." can be
 * built from checks that never ran.
 */
const attentionCopy = computed(() =>
  attentionEmpty({
    loading: healthLoading.value,
    failed: error.value !== null,
    count: attention.value.length,
    unavailable: unavailable.value.map((k) => k.label),
    clean: copy.value.clean,
  }),
);

const EVENT_LIMIT = 25;
const eventData = computed(() => checkedData(events.value));
const recentEvents = computed(() => (eventData.value ? eventData.value.slice(0, EVENT_LIMIT) : null));
const eventsTruncated = computed(() => (eventData.value?.length ?? 0) > EVENT_LIMIT);
const eventsUnchecked = computed(() => uncheckedNote(events.value, 'events'));
</script>

<template>
  <div class="overview">
    <h1 class="ov-title">{{ copy.title }}</h1>
    <!-- Name the scope. A filtered page that looks identical to an unfiltered one is how a
         reading gets trusted for the wrong cluster slice. -->
    <div class="ov-scope-note">{{ namespace ? `Namespace: ${namespace}` : 'All namespaces' }}</div>

    <ErrorNotice v-if="error" :message="error" :retrying="healthLoading" @retry="reloadHealth" />

    <!-- Cards first: they are the orientation — how much is here, and how much of it is fine —
         and the table below is the detail. The Cluster overview reads the same way. -->
    <div class="ov-cards">
      <StatCard
        v-for="c in cards"
        :key="c.id"
        :value="c.value"
        :label="c.label"
        :states="c.states"
        clickable
        states-clickable
        @select="emit('navigate', c.kind)"
        @select-state="(q) => emit('navigate-state', c.kind, q)"
      />
    </div>

    <!-- Then the detail: what is wrong, named. -->
    <section v-if="health" class="ov-sec">
      <h3>{{ copy.attention }}</h3>
      <EmptyState v-if="attentionCopy" :title="attentionCopy.title" :body="attentionCopy.body" variant="inline" />
      <template v-else-if="attention.length > 0">
        <!-- `.mini-scroll` is the table's own sideways scroller — see the wrap policy in
             styles.css (#278). -->
        <div class="mini-scroll">
          <table class="mini attention-table">
            <thead>
              <tr>
                <th>Kind</th>
                <th>Namespace</th>
                <th>Name</th>
                <th>Reason</th>
              </tr>
            </thead>
            <tbody>
              <tr
                v-for="(it, i) in attention"
                :key="i"
                tabindex="0"
                :title="`Show ${it.kind} in ${it.namespace ?? 'this cluster'}`"
                @click="emit('navigate', it.kind, it.namespace ?? undefined)"
                @keydown.enter="emit('navigate', it.kind, it.namespace ?? undefined)"
              >
                <td>{{ it.kind }}</td>
                <td>{{ it.namespace ?? '—' }}</td>
                <td>{{ it.name }}</td>
                <td class="attention-reason">{{ it.reason }}</td>
              </tr>
            </tbody>
          </table>
        </div>
        <div v-if="attentionTruncated" class="ov-truncated">Some kinds have more affected objects than shown.</div>
      </template>
      <!-- A kind that could not be listed is called out, because a missing check must not be
           mistaken for a clean bill of health. -->
      <div v-if="unavailable.length > 0" class="ov-truncated">
        Could not check: {{ unavailable.map((k) => k.label).join(', ') }}.
      </div>
      <!-- What the check does NOT cover, next to the result rather than out of sight. -->
      <ul v-if="copy.notes" class="ov-notes">
        <li v-for="(note, i) in copy.notes" :key="i">{{ note }}</li>
      </ul>
    </section>

    <section v-if="showEvents" class="ov-sec">
      <h3>Recent Events</h3>
      <div v-if="eventsTruncated" class="ov-truncated">
        Showing the {{ EVENT_LIMIT }} most recent of {{ eventData?.length }} events.
      </div>
      <!-- The pane distinguishes "loaded nothing" from "could not load"; give it the truth. -->
      <EventsPane :events="recentEvents" :error="eventsUnchecked" @retry="loadEvents" />
    </section>
  </div>
</template>
