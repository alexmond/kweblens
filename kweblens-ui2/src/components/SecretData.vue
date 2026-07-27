<script setup lang="ts">
import { reactive } from 'vue';

// Secret data table — base64 values are masked until per-key Reveal decodes them.
defineProps<{ data: Record<string, string> }>();

const revealed = reactive<Record<string, boolean>>({});
const decode = (v: string): string => {
  try {
    return atob(v);
  } catch {
    return '‹binary›';
  }
};
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
        <td class="mono">{{ revealed[k] ? decode(val) : '••••••••' }}</td>
        <td>
          <button class="linkbtn" @click="revealed[k] = !revealed[k]">{{ revealed[k] ? 'Hide' : 'Reveal' }}</button>
        </td>
      </tr>
    </tbody>
  </table>
</template>
