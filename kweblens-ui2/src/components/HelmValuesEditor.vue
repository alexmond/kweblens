<script setup lang="ts">
import { ref } from 'vue';

import { api } from '../api';
import type { HelmAction } from './helm-types';

// The values-YAML editor: load a saved set from the library / a release's current values,
// edit inline, save back to the library. `valuesYaml` and `savedValues` are two-way models.
const props = defineProps<{ cluster: string; action: HelmAction }>();
const valuesYaml = defineModel<string>('valuesYaml', { required: true });
const savedValues = defineModel<string[]>('savedValues', { required: true });

const pickValues = ref('');
const saveName = ref('');
const valuesMsg = ref<string | null>(null);

const loadSaved = () => {
  api
    .helmValuesGet(pickValues.value)
    .then((y) => {
      valuesYaml.value = y;
      valuesMsg.value = `loaded "${pickValues.value}"`;
    })
    .catch((e) => (valuesMsg.value = String(e)));
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
    .catch((e) => (valuesMsg.value = String(e)));
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
    .catch((e) => (valuesMsg.value = String(e)));
};
</script>

<template>
  <label>
    <span>Values (YAML, optional)</span>
    <div class="values-toolbar">
      <select v-model="pickValues">
        <option value="">— saved values —</option>
        <option v-for="n in savedValues" :key="n" :value="n">{{ n }}</option>
      </select>
      <button type="button" class="btn" :disabled="!pickValues" @click="loadSaved">Load</button>
      <button v-if="action.mode === 'upgrade'" type="button" class="btn" @click="loadCurrent">
        Load current values
      </button>
      <span class="tb-spacer" />
      <input v-model="saveName" class="save-name" placeholder="save as…" />
      <button type="button" class="btn" :disabled="!saveName.trim()" @click="save">Save</button>
    </div>
    <div v-if="valuesMsg" class="values-msg">{{ valuesMsg }}</div>
    <textarea v-model="valuesYaml" class="values" :rows="5" placeholder="key: value" />
  </label>
</template>
