<script setup lang="ts">
// One collapsible section of the Overview tab, rendered from the descriptor `overview.ts`
// produced. Its own component for the same reason as OverviewField.vue: the wide layout
// (#232) renders sections into two columns, and the body's five shapes are far too much
// markup to keep in two places.
import Chips from './Chips.vue';
import SecretData from './SecretData.vue';
import Accordion from './Accordion.vue';
import type { OvBody } from './overview';

defineProps<{ title: string; count?: number; defaultOpen?: boolean; body: OvBody }>();

const clip = (v: string) => (v.length > 48 ? v.slice(0, 48) + '…' : v);
</script>

<template>
  <Accordion :title="title" :count="count" :default-open="defaultOpen">
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
      <!-- Why a column a reader might expect is absent (#248). A table that quietly drops
           `Ready`/`Restarts` is honest; one that also says why is useful. -->
      <div v-if="body.note" class="rel-note dim">{{ body.note }}</div>
    </template>
  </Accordion>
</template>
