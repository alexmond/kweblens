<script setup lang="ts">
import { reactive } from 'vue';

import { secretValueText } from './overview';

// Secret data table — base64 values are masked until per-key Reveal decodes them. The masking
// and decoding are in `overview.ts` so they can be tested without a DOM; this file renders.
//
// A value is `null` when the row came from a LIST payload, which ships the keys without the
// values (GH#276). The drawer refetches the whole object, so that is a brief state, but it
// renders as "not loaded" rather than as an empty value, and Reveal is withheld — a button
// that can only ever decode nothing is worse than no button.
defineProps<{ data: Record<string, string | null> }>();

const revealed = reactive<Record<string, boolean>>({});
</script>

<template>
  <table class="mini">
    <thead>
      <tr>
        <th>Key</th>
        <th>Value</th>
        <th />
      </tr>
    </thead>
    <tbody>
      <tr v-for="(val, k) in data" :key="k">
        <td class="mono">{{ k }}</td>
        <td class="mono">{{ secretValueText(val, revealed[k] ?? false) }}</td>
        <td>
          <button v-if="val !== null" class="linkbtn" @click="revealed[k] = !revealed[k]">
            {{ revealed[k] ? 'Hide' : 'Reveal' }}
          </button>
        </td>
      </tr>
    </tbody>
  </table>
</template>
