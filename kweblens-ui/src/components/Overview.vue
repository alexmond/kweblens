<script setup lang="ts">
// Renders the detail drawer's Overview tab purely by mapping the field + section
// registries in `overview.ts` — no per-kind markup lives here.
//
// Emits (mirrors the React `onNavigate` / `onHelmRelease` callback props):
//   navigate     (kind: string, ns?: string)         — open another kind/object list
//   helm-release (namespace: string, name: string)   — open a Helm release's resources
import { NTag } from 'naive-ui';
import { computed } from 'vue';

import type { KubeObject } from '../types';
import Accordion from './Accordion.vue';
import Chips from './Chips.vue';
import SecretData from './SecretData.vue';
import { OVERVIEW_FIELDS, OVERVIEW_SECTIONS } from './overview';

const props = defineProps<{ obj: KubeObject }>();
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
  </div>
</template>
