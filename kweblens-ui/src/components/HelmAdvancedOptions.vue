<script setup lang="ts">
import { NButton, NCheckbox, NFormItem, NInput, NSelect } from 'naive-ui';
import type { SelectOption } from 'naive-ui';
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

const strategyOptions: SelectOption[] = [
  { label: 'Default', value: '' },
  { label: 'Reuse previous values', value: 'REUSE' },
  { label: 'Reset to chart defaults', value: 'RESET' },
  { label: 'Reset, then reuse', value: 'RESET_THEN_REUSE' },
];
</script>

<template>
  <div class="adv-options">
    <NButton text :aria-expanded="showAdvanced" @click="showAdvanced = !showAdvanced">
      {{ showAdvanced ? '▾' : '▸' }} Advanced options
    </NButton>
    <div v-if="showAdvanced" class="adv-body">
      <NCheckbox v-model:checked="noHooks">Skip hooks (--no-hooks)</NCheckbox>
      <template v-if="isUpgrade">
        <NCheckbox v-model:checked="force">Force resource updates (--force)</NCheckbox>
        <NFormItem label="Values strategy">
          <NSelect v-model:value="valueStrategy" :options="strategyOptions" style="max-width: 260px" />
        </NFormItem>
        <NFormItem label="Max history (0 = keep default)">
          <NInput v-model:value="maxHistory" placeholder="0" style="max-width: 160px" />
        </NFormItem>
      </template>
      <NFormItem label="Description (optional)">
        <NInput v-model:value="description" placeholder="release description" />
      </NFormItem>
    </div>
  </div>
</template>
