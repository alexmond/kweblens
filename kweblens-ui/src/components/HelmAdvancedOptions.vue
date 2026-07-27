<script setup lang="ts">
import { ref } from 'vue';

// Install/upgrade advanced options (map to jhelm InstallOptions / UpgradeOptions). Every
// field is a two-way model; `isUpgrade` gates the upgrade-only options.
defineProps<{ isUpgrade: boolean }>();
const noHooks = defineModel<boolean>('noHooks', { required: true });
const force = defineModel<boolean>('force', { required: true });
const valueStrategy = defineModel<string>('valueStrategy', { required: true });
const maxHistory = defineModel<string>('maxHistory', { required: true });
const description = defineModel<string>('description', { required: true });

const showAdvanced = ref(false);
</script>

<template>
  <div class="adv-options">
    <button type="button" class="adv-toggle" :aria-expanded="showAdvanced" @click="showAdvanced = !showAdvanced">
      {{ showAdvanced ? '▾' : '▸' }} Advanced options
    </button>
    <div v-if="showAdvanced" class="adv-body">
      <label class="check">
        <input v-model="noHooks" type="checkbox" />
        <span>Skip hooks (--no-hooks)</span>
      </label>
      <template v-if="isUpgrade">
        <label class="check">
          <input v-model="force" type="checkbox" />
          <span>Force resource updates (--force)</span>
        </label>
        <label>
          <span>Values strategy</span>
          <select v-model="valueStrategy">
            <option value="">Default</option>
            <option value="REUSE">Reuse previous values</option>
            <option value="RESET">Reset to chart defaults</option>
            <option value="RESET_THEN_REUSE">Reset, then reuse</option>
          </select>
        </label>
        <label>
          <span>Max history (0 = keep default)</span>
          <input v-model="maxHistory" type="number" :min="0" placeholder="0" />
        </label>
      </template>
      <label>
        <span>Description (optional)</span>
        <input v-model="description" placeholder="release description" />
      </label>
    </div>
  </div>
</template>
