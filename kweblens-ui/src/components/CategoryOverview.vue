<script setup lang="ts">
// One dashboard for every category — Workloads, Network, Storage, Config.
//
// Leads with what NEEDS ATTENTION and names the objects, then shows the totals: a count is only
// interesting when it is unexpected, and "one of your 52 Services is broken" without saying which
// one just moves the hunt one step later.
//
// The checks are computed SERVER-SIDE (/overview/<category>) and they differ completely per
// category — a workload verdict, a Service-to-Endpoints join, a PVC phase, a reverse reference
// scan — but they all answer the same shape of question, so one component renders all of them and
// the same summaries can serve a future TUI and the agent.
import { shallowRef, computed, watch } from 'vue';

import { api } from '../api';
import type { EventSummary, KindHealth } from '../types';
import EventsPane from './EventsPane.vue';
import StatCard from './StatCard.vue';
import { OVERVIEW_CATEGORIES } from './overviewCategories';

const props = defineProps<{ cluster: string; category: string; namespace?: string | null }>();

// Emitted with the kind (and namespace, for a named object) the user asked to see. The shell owns
// navigation; the overview only says where it wants to go.
const emit = defineEmits<{ (e: 'navigate', kind: string, namespace?: string): void }>();

const health = shallowRef<KindHealth[] | null>(null);
const events = shallowRef<EventSummary[] | null>(null);
const error = shallowRef<string | null>(null);

const copy = computed(() => OVERVIEW_CATEGORIES[props.category]);
/** Events are about workloads; on a storage or config page they would be noise. */
const showEvents = computed(() => props.category === 'workloads');

let reqId = 0;
watch(
  // Re-fetch on namespace as well as cluster: the filter is in the header the whole time this
  // page is open, so ignoring it here made it look broken rather than inapplicable.
  () => [props.cluster, props.category, props.namespace] as const,
  ([cluster, category, namespace]) => {
    const my = ++reqId;
    health.value = null;
    events.value = null;
    error.value = null;
    const ns = namespace ?? undefined;
    api
      .overview(cluster, category, ns)
      .then((h) => my === reqId && (health.value = h))
      .catch((e) => my === reqId && (error.value = String(e)));
    if (showEvents.value) {
      api
        .events(cluster, ns)
        .then((e) => my === reqId && (events.value = e))
        .catch(() => my === reqId && (events.value = []));
    }
  },
  { immediate: true },
);

const cards = computed(() =>
  (health.value ?? []).map((k) => {
    if (k.error) {
      // "Could not check" must never render as a healthy zero. Still clickable: the list page is
      // where the actual error is reported, so it is the useful next step.
      return { id: k.id, kind: k.kind, value: '—', label: `${k.label} · unavailable`, danger: false };
    }
    const parts = [`${k.ok} ok`];
    if (k.attention > 0) {
      parts.push(copy.value.advisory ? `${k.attention} unreferenced` : `${k.attention} need attention`);
    }
    if (k.suspended > 0) {
      parts.push(`${k.suspended} suspended`);
    }
    return {
      id: k.id,
      kind: k.kind,
      value: k.total,
      label: `${k.label} · ${parts.join(' · ')}`,
      // Advisory findings are candidates for review, not faults — see OVERVIEW_CATEGORIES.
      danger: k.attention > 0 && !copy.value.advisory,
    };
  }),
);

/** Everything needing attention, across kinds — the thing the page should open with. */
const attention = computed(() => (health.value ?? []).flatMap((k) => k.needsAttention));
const attentionTruncated = computed(() => (health.value ?? []).some((k) => k.truncated));
const unavailable = computed(() => (health.value ?? []).filter((k) => k.error));

const EVENT_LIMIT = 25;
const recentEvents = computed(() => (events.value ? events.value.slice(0, EVENT_LIMIT) : null));
const eventsTruncated = computed(() => (events.value?.length ?? 0) > EVENT_LIMIT);
</script>

<template>
  <div class="overview">
    <h1 class="ov-title">{{ copy.title }}</h1>
    <!-- Name the scope. A filtered page that looks identical to an unfiltered one is how a
         reading gets trusted for the wrong cluster slice. -->
    <div class="ov-scope-note">{{ namespace ? `Namespace: ${namespace}` : 'All namespaces' }}</div>

    <div v-if="error" class="error">{{ error }}</div>

    <!-- Needs attention FIRST. This is the question the page exists to answer. -->
    <section v-if="health" class="ov-sec">
      <h3>{{ copy.attention }}</h3>
      <div v-if="attention.length === 0" class="empty">{{ copy.clean }}</div>
      <template v-else>
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

    <div class="ov-cards">
      <StatCard
        v-for="c in cards"
        :key="c.id"
        :value="c.value"
        :label="c.label"
        :danger="c.danger"
        clickable
        @select="emit('navigate', c.kind)"
      />
    </div>

    <section v-if="showEvents" class="ov-sec">
      <h3>Recent Events</h3>
      <div v-if="eventsTruncated" class="ov-truncated">
        Showing the {{ EVENT_LIMIT }} most recent of {{ events?.length }} events.
      </div>
      <EventsPane :events="recentEvents" :error="null" />
    </section>
  </div>
</template>
