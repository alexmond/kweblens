<script setup lang="ts">
// One relation-backed section (GH#136) of the Overview tab. Carries its own state, so one
// unreadable kind degrades to a message in its own section instead of blanking the drawer.
//
// Split out alongside OverviewSection.vue so #232's two-column layout can place a relation
// in either column by where it renders the component, without a second copy of the markup.
import Accordion from './Accordion.vue';
import type { RelationSection } from './relations';

defineProps<{ section: RelationSection }>();
</script>

<template>
  <Accordion :title="section.title" :count="section.count" :default-open="true">
    <div v-if="section.message" :class="section.notPermitted ? 'rel-note rel-denied' : 'rel-note rel-error'">
      {{ section.message }}
    </div>
    <template v-else-if="section.rows.length === 0">
      <div class="rel-note dim">None.</div>
    </template>
    <template v-else>
      <table class="mini">
        <thead>
          <tr>
            <th v-for="h in section.headers" :key="h">{{ h }}</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="(row, ri) in section.rows" :key="ri">
            <td v-for="(cell, ci) in row" :key="ci" :class="cell.mono ? 'mono' : undefined">{{ cell.text }}</td>
          </tr>
        </tbody>
      </table>
      <div v-if="section.truncated" class="rel-note rel-error">
        Showing the first {{ section.rows.length }} only — there are more.
      </div>
    </template>
  </Accordion>
</template>
