<script setup lang="ts">
import { NButton, NFormItem, NInput, NSelect } from 'naive-ui';
import type { SelectOption } from 'naive-ui';
import { computed, ref } from 'vue';

import { api } from '../api';
import { failureNotice } from '../apiFailure';
import type { HelmAction } from './helm-types';

// The values-YAML editor: load a saved set from the library / a release's current values,
// edit inline, save back to the library. `valuesYaml` and `savedValues` are two-way models.
const props = defineProps<{ cluster: string; action: HelmAction }>();
const valuesYaml = defineModel<string>('valuesYaml', { required: true });
const savedValues = defineModel<string[]>('savedValues', { required: true });

const pickValues = ref('');
const saveName = ref('');
const valuesMsg = ref<string | null>(null);

const pickOptions = computed<SelectOption[]>(() => [
  { label: '— saved values —', value: '' },
  ...savedValues.value.map((n) => ({ label: n, value: n })),
]);

const loadSaved = () => {
  api
    .helmValuesGet(pickValues.value)
    .then((y) => {
      valuesYaml.value = y;
      valuesMsg.value = `loaded "${pickValues.value}"`;
    })
    .catch((e) => (valuesMsg.value = failureNotice(e)));
};

const loadCurrent = () => {
  if (props.action.mode !== 'upgrade') {
    return;
  }
  const { namespace, name } = props.action;
  api
    .helmReleaseValues(props.cluster, namespace, name)
    .then((y) => {
      valuesYaml.value = y;
      valuesMsg.value = 'loaded current release values';
    })
    .catch((e) => (valuesMsg.value = failureNotice(e)));
};

const save = () => {
  const n = saveName.value.trim();
  api
    .helmValuesSave(n, valuesYaml.value)
    .then(() => {
      valuesMsg.value = `saved "${n}"`;
      saveName.value = '';
      return api.helmValuesList().then((list) => (savedValues.value = list));
    })
    .catch((e) => (valuesMsg.value = failureNotice(e)));
};
</script>

<template>
  <NFormItem label="Values (YAML, optional)">
    <div style="width: 100%">
      <div class="values-toolbar">
        <NSelect v-model:value="pickValues" :options="pickOptions" size="small" style="max-width: 220px" />
        <NButton size="small" :disabled="!pickValues" @click="loadSaved">Load</NButton>
        <NButton v-if="action.mode === 'upgrade'" size="small" @click="loadCurrent">Load current values</NButton>
        <span class="tb-spacer" />
        <NInput v-model:value="saveName" class="save-name" size="small" placeholder="save as…" />
        <NButton size="small" :disabled="!saveName.trim()" @click="save">Save</NButton>
      </div>
      <div v-if="valuesMsg" class="values-msg">{{ valuesMsg }}</div>
      <NInput
        v-model:value="valuesYaml"
        type="textarea"
        class="values"
        :autosize="{ minRows: 5 }"
        placeholder="key: value"
      />
    </div>
  </NFormItem>
</template>
