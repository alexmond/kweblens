<script setup lang="ts">
// Renders the detail drawer's Overview tab purely by mapping the field + section
// registries in `overview.ts` — no per-kind markup lives here.
//
// LAYOUT (#232). The same DOM renders two ways, decided by the width of the `kw-pane` this
// sits in (`responsive.ts`), never by measuring anything here:
//
//   narrow  one stacked column, exactly as before — `.ov-main` and `.ov-aside` are
//           `display: contents`, so their children flow as if the wrappers were not there.
//   wide    a main column with the object's substance, and an aside with provenance.
//
// Which is which comes from `rankOf` via `splitByRank`, so a section's own registry entry
// decides where it lands and nothing is re-derived from its title. Every section exists in
// both layouts — the wide one MOVES sections, it never hides one — and because the reflow
// is pure CSS over one set of components, a section collapsed by the reader stays collapsed
// across it.
//
// Emits (mirrors the React `onNavigate` / `onHelmRelease` callback props):
//   navigate     (kind: string, ns?: string)         — open another kind/object list
//   helm-release (namespace: string, name: string)   — open a Helm release's resources
import { computed, ref, watch } from 'vue';

import { api } from '../api';
import { failureNotice } from '../apiFailure';
import { objName, objNs } from '../kube';
import type { KubeObject, Relation } from '../types';
import OverviewField from './OverviewField.vue';
import OverviewSection from './OverviewSection.vue';
import RelationSectionView from './RelationSectionView.vue';
import { OVERVIEW_FIELDS, OVERVIEW_SECTIONS, splitByRank } from './overview';
import { relationSections } from './relations';

const props = defineProps<{ obj: KubeObject; cluster?: string; resourceId?: string }>();
const emit = defineEmits<{
  (e: 'navigate', kind: string, ns?: string): void;
  (e: 'helm-release', namespace: string, name: string): void;
}>();

const fields = computed(() =>
  OVERVIEW_FIELDS.map((f) => ({ field: f, value: f.get(props.obj) })).filter((r) => r.value !== null),
);

const sections = computed(() =>
  OVERVIEW_SECTIONS.filter((s) => s.applies(props.obj)).map((s) => ({
    section: s,
    count: s.count?.(props.obj),
    body: s.body(props.obj),
  })),
);

// ---- Relations (GH#136) ----
// Fetched rather than derived, because they need a second object: which pods back this
// Service, what mounts this Secret, which pods the selector matches. One request to the
// detail endpoint, which does the joins server-side and bounds their cost.
const relations = ref<Record<string, Relation> | undefined>(undefined);
const relationsLoading = ref(false);
const relationsError = ref<string | null>(null);

watch(
  // A missing namespace is NOT a reason to skip the fetch (#313). `boundClaim` resolves from
  // a PersistentVolume, which is cluster-scoped, so gating on one left a relation the server
  // resolves unreachable from this client — never asked for, rather than empty or errored.
  // The route still needs some segment there; `api.detail` puts the `_` sentinel in it.
  () => [props.cluster, props.resourceId, objNs(props.obj), objName(props.obj)],
  ([cluster, resourceId, namespace, name]) => {
    relations.value = undefined;
    relationsError.value = null;
    if (!cluster || !resourceId || !name) {
      return;
    }
    relationsLoading.value = true;
    api
      .detail(cluster, resourceId, namespace, name)
      .then((d) => (relations.value = d.relations))
      .catch((e) => (relationsError.value = failureNotice(e)))
      .finally(() => (relationsLoading.value = false));
  },
  { immediate: true },
);

const relSections = computed(() => relationSections(relations.value));

// The rank split, run over all three kinds of entry through the one call. Relation sections
// carry no rank and so are primary by definition (see `relations.ts`) — running them through
// `splitByRank` anyway is what keeps that a property of the registry rather than a rule
// spelled out again here.
const fieldSplit = computed(() => splitByRank(fields.value, (f) => f.field));
const sectionSplit = computed(() => splitByRank(sections.value, (s) => s.section));
const relationSplit = computed(() => splitByRank(relSections.value, (r) => r));

// Without an aside there is nothing to put in a second column, so the wide layout does not
// reserve one — an object with no labels, no annotations and no provenance would otherwise
// get 320px of empty gutter.
const hasAside = computed(
  () => fieldSplit.value.aside.length + sectionSplit.value.aside.length + relationSplit.value.aside.length > 0,
);
</script>

<template>
  <div class="ov" :class="hasAside ? 'ov-split' : undefined">
    <dl class="kv ov-kv">
      <OverviewField
        v-for="{ field, value } in fieldSplit.main"
        :key="field.label"
        :field="field"
        :value="value!"
        @navigate="(k, n) => emit('navigate', k, n)"
        @helm-release="(nsp, nm) => emit('helm-release', nsp, nm)"
      />
    </dl>

    <!-- Provenance and bookkeeping. Sits here in the DOM, between the summary rows and the
         sections, because at narrow the wrappers are `display: contents` and this is where
         Labels and Annotations have always appeared. -->
    <aside v-if="hasAside" class="ov-aside">
      <dl v-if="fieldSplit.aside.length > 0" class="kv ov-aside-kv">
        <OverviewField
          v-for="{ field, value } in fieldSplit.aside"
          :key="field.label"
          :field="field"
          :value="value!"
          @navigate="(k, n) => emit('navigate', k, n)"
          @helm-release="(nsp, nm) => emit('helm-release', nsp, nm)"
        />
      </dl>
      <OverviewSection
        v-for="({ section, count, body }, i) in sectionSplit.aside"
        :key="section.title + i"
        :title="section.title"
        :count="count"
        :default-open="section.defaultOpen"
        :body="body"
      />
      <RelationSectionView v-for="rel in relationSplit.aside" :key="'rel:' + rel.title" :section="rel" />
    </aside>

    <div class="ov-main">
      <OverviewSection
        v-for="({ section, count, body }, i) in sectionSplit.main"
        :key="section.title + i"
        :title="section.title"
        :count="count"
        :default-open="section.defaultOpen"
        :body="body"
      />
      <RelationSectionView v-for="rel in relationSplit.main" :key="'rel:' + rel.title" :section="rel" />
      <!-- The joins cost a round trip, so say the view is still filling in rather than briefly
           implying an object has no relations. -->
      <div v-if="relationsLoading" class="rel-note dim">Loading related objects…</div>
      <div v-if="relationsError" class="rel-note rel-error">Could not load related objects: {{ relationsError }}</div>
    </div>
  </div>
</template>
