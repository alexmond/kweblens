<script setup lang="ts">
// Renders the detail drawer's Overview tab purely by mapping the field + section
// registries in `overview.ts` — no per-kind markup lives here.
//
// Emits (mirrors the React `onNavigate` / `onHelmRelease` callback props):
//   navigate     (kind: string, ns?: string)         — open another kind/object list
//   helm-release (namespace: string, name: string)   — open a Helm release's resources
import { NTag } from 'naive-ui';
import { computed, ref, watch } from 'vue';

import { api } from '../api';
import { objName, objNs } from '../kube';
import type { KubeObject, Relation } from '../types';
import Accordion from './Accordion.vue';
import Chips from './Chips.vue';
import SecretData from './SecretData.vue';
import { OVERVIEW_FIELDS, OVERVIEW_SECTIONS } from './overview';
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
  // Namespaced objects only: the relations resolved today are all namespace-scoped, and the
  // endpoint's path requires a namespace.
  () => [props.cluster, props.resourceId, objNs(props.obj), objName(props.obj)],
  ([cluster, resourceId, namespace, name]) => {
    relations.value = undefined;
    relationsError.value = null;
    if (!cluster || !resourceId || !namespace || !name) {
      return;
    }
    relationsLoading.value = true;
    api
      .detail(cluster, resourceId, namespace, name)
      .then((d) => (relations.value = d.relations))
      .catch((e) => (relationsError.value = String(e)))
      .finally(() => (relationsLoading.value = false));
  },
  { immediate: true },
);

const relSections = computed(() => relationSections(relations.value));

const clip = (v: string) => (v.length > 48 ? v.slice(0, 48) + '…' : v);
</script>

<template>
  <div class="ov">
    <dl class="kv">
      <template v-for="{ field, value } in fields" :key="field.label">
        <dt>{{ field.label }}</dt>
        <dd :class="field.mono ? 'mono' : undefined">
          <template v-if="value!.kind === 'text'">{{ value!.text }}</template>
          <button
            v-else-if="value!.kind === 'nav'"
            class="cell-link"
            @click="emit('navigate', value!.navKind, value!.navNs)"
          >
            {{ value!.text }}
          </button>
          <template v-else-if="value!.kind === 'helm'">
            <NTag size="small" type="info" :bordered="false">Helm</NTag>{{ ' ' }}
            <button
              class="cell-link"
              title="Open this release's resources"
              @click="emit('helm-release', value!.rns, value!.rel)"
            >
              {{ value!.rel }}{{ value!.rns ? ` (${value!.rns})` : '' }}
            </button>
          </template>
          <template v-else-if="value!.kind === 'owners'">
            <span v-for="(o, i) in value!.owners" :key="o.kind + '/' + o.name">
              {{ i > 0 ? ', ' : '' }}
              <button class="cell-link" @click="emit('navigate', o.kind, o.ns)">{{ o.kind }}/{{ o.name }}</button>
            </span>
          </template>
        </dd>
      </template>
    </dl>

    <Accordion
      v-for="({ section, count, body }, i) in sections"
      :key="section.title + i"
      :title="section.title"
      :count="count"
      :default-open="section.defaultOpen"
    >
      <Chips v-if="body.type === 'chips'" :map="body.map" />
      <div v-else-if="body.type === 'annotations'" class="chips">
        <span v-for="(v, k) in body.map" :key="k" class="chip subtle" :title="`${k}=${v}`">{{ k }}={{ clip(v) }}</span>
      </div>
      <SecretData v-else-if="body.type === 'secret'" :data="body.data" />
      <dl v-else-if="body.type === 'kv'" class="kv">
        <template v-for="[k, v] in body.pairs" :key="k">
          <dt>{{ k }}</dt>
          <dd>{{ v }}</dd>
        </template>
      </dl>
      <template v-else-if="body.type === 'table'">
        <table class="mini">
          <thead>
            <tr>
              <th v-for="h in body.headers" :key="h">{{ h }}</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="(row, ri) in body.rows" :key="ri">
              <td v-for="(cell, ci) in row" :key="ci" :class="cell.mono ? 'mono' : undefined">{{ cell.text }}</td>
            </tr>
          </tbody>
        </table>
        <div v-if="body.tls && body.tls.length > 0" class="chips" :style="{ marginTop: '8px' }">
          <span v-for="(h, ti) in body.tls" :key="ti + h" class="chip">TLS: {{ h }}</span>
        </div>
      </template>
    </Accordion>

    <!-- Relation-backed sections. Each carries its own state, so one unreadable kind degrades
         to a message in its own section instead of blanking the drawer. -->
    <Accordion
      v-for="rel in relSections"
      :key="'rel:' + rel.title"
      :title="rel.title"
      :count="rel.count"
      :default-open="true"
    >
      <div v-if="rel.message" :class="rel.notPermitted ? 'rel-note rel-denied' : 'rel-note rel-error'">
        {{ rel.message }}
      </div>
      <template v-else-if="rel.rows.length === 0">
        <div class="rel-note dim">None.</div>
      </template>
      <template v-else>
        <table class="mini">
          <thead>
            <tr>
              <th v-for="h in rel.headers" :key="h">{{ h }}</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="(row, ri) in rel.rows" :key="ri">
              <td v-for="(cell, ci) in row" :key="ci" :class="cell.mono ? 'mono' : undefined">{{ cell.text }}</td>
            </tr>
          </tbody>
        </table>
        <div v-if="rel.truncated" class="rel-note rel-error">
          Showing the first {{ rel.rows.length }} only — there are more.
        </div>
      </template>
    </Accordion>
    <!-- The joins cost a round trip, so say the view is still filling in rather than briefly
         implying an object has no relations. -->
    <div v-if="relationsLoading" class="rel-note dim">Loading related objects…</div>
    <div v-if="relationsError" class="rel-note rel-error">Could not load related objects: {{ relationsError }}</div>
  </div>
</template>
