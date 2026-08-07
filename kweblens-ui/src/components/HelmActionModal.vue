<script setup lang="ts">
import { NButton, NCheckbox, NForm, NFormItem, NInput, NInputNumber, NModal } from 'naive-ui';
import { shallowRef, onMounted, ref } from 'vue';

import { api } from '../api';
import { failureNotice, isSessionExpiry } from '../apiFailure';
import HelmValuesEditor from './HelmValuesEditor.vue';
import HelmAdvancedOptions from './HelmAdvancedOptions.vue';
import YamlView from './YamlView.vue';
import type { HelmAction } from './helm-types';
import type { HelmMutationResult } from '../types';

// Install / upgrade / rollback a release with a values editor, advanced options and a
// mandatory dry-run: Apply is enabled only once a dry-run render succeeds.
//
// Events emitted:
//   (e: 'close'): void
//   (e: 'applied'): void        — a non-dry-run mutation succeeded
//   (e: 'auth-expired'): void    — a mutation returned 401
const props = defineProps<{ cluster: string; action: HelmAction }>();
const emit = defineEmits<{ (e: 'close'): void; (e: 'applied'): void; (e: 'auth-expired'): void }>();

// Seed the form fields from the action (chart/version/repo per mode).
function initialFormState(action: HelmAction) {
  if (action.mode === 'install') {
    return {
      releaseName: action.chart,
      repository: action.repository,
      chart: action.chart,
      version: action.version,
      revision: 1,
    };
  }
  if (action.mode === 'upgrade') {
    return {
      releaseName: '',
      repository: action.repository ?? '',
      chart: action.chart,
      version: action.version ?? action.chartVersion,
      revision: 1,
    };
  }
  return { releaseName: '', repository: '', chart: '', version: '', revision: action.revision };
}

const init = initialFormState(props.action);
const releaseName = ref(init.releaseName);
const namespace = ref('default');
const repository = ref(init.repository);
const chart = ref(init.chart);
const version = ref(init.version);
const valuesYaml = ref('');
const savedValues = shallowRef<string[]>([]);
const revision = ref(init.revision);
const createNamespace = ref(false);
// Advanced options (map to jhelm InstallOptions / UpgradeOptions).
const noHooks = ref(false);
const description = ref('');
const force = ref(false);
const valueStrategy = ref('');
const maxHistory = ref('');
const preview = ref<HelmMutationResult | null>(null);
const busy = ref(false);
const error = ref<string | null>(null);

onMounted(() => {
  api
    .helmValuesList()
    .then((list) => (savedValues.value = list))
    .catch(() => (savedValues.value = []));
});

const title =
  props.action.mode === 'install'
    ? 'Install chart'
    : props.action.mode === 'upgrade'
      ? 'Upgrade release'
      : 'Rollback release';

const applyLabel =
  props.action.mode === 'install' ? 'Install' : props.action.mode === 'upgrade' ? 'Upgrade' : 'Rollback';

const mutation = (dryRun: boolean): Promise<HelmMutationResult> => {
  const a = props.action;
  const maxHist = Number.parseInt(maxHistory.value, 10);
  if (a.mode === 'install') {
    return api.helmInstall(props.cluster, {
      namespace: namespace.value,
      releaseName: releaseName.value,
      repository: repository.value,
      chart: chart.value,
      version: version.value,
      valuesYaml: valuesYaml.value,
      dryRun,
      createNamespace: createNamespace.value,
      noHooks: noHooks.value,
      description: description.value.trim() || undefined,
    });
  }
  if (a.mode === 'upgrade') {
    return api.helmUpgrade(props.cluster, a.namespace, a.name, {
      repository: repository.value,
      chart: chart.value,
      version: version.value,
      valuesYaml: valuesYaml.value,
      dryRun,
      noHooks: noHooks.value,
      force: force.value,
      valueStrategy: valueStrategy.value || undefined,
      maxHistory: Number.isNaN(maxHist) ? undefined : maxHist,
      description: description.value.trim() || undefined,
    });
  }
  return api.helmRollback(props.cluster, a.namespace, a.name, { revision: revision.value, dryRun });
};

const run = (dryRun: boolean) => {
  busy.value = true;
  error.value = null;
  mutation(dryRun)
    .then((res) => {
      if (dryRun) {
        preview.value = res;
      } else {
        emit('applied');
      }
    })
    .catch((err) => {
      if (isSessionExpiry(err)) {
        emit('auth-expired');
      }
      // The dry run goes through this same path, so a chart or values file the cluster
      // rejects has no other surface that would print the reason — it has to be here.
      error.value = failureNotice(err);
    })
    .finally(() => (busy.value = false));
};
</script>

<template>
  <NModal
    :show="true"
    preset="card"
    :title="title"
    style="width: 780px; max-width: 94vw"
    @update:show="(v) => !v && emit('close')"
  >
    <p class="modal-note">Preview a dry-run first; Apply is enabled once the render succeeds.</p>
    <div v-if="error" class="error">{{ error }}</div>

    <NForm label-placement="top" :show-feedback="false">
      <template v-if="action.mode === 'install'">
        <NFormItem label="Chart">
          <NInput :value="`${repository}/${chart}`" readonly />
        </NFormItem>
        <NFormItem label="Version">
          <NInput v-model:value="version" />
        </NFormItem>
        <NFormItem label="Release name">
          <NInput v-model:value="releaseName" />
        </NFormItem>
        <NFormItem label="Namespace">
          <NInput v-model:value="namespace" />
        </NFormItem>
        <NFormItem :show-label="false">
          <NCheckbox v-model:checked="createNamespace">Create namespace if missing</NCheckbox>
        </NFormItem>
      </template>
      <template v-else-if="action.mode === 'upgrade'">
        <NFormItem label="Release">
          <NInput :value="`${action.namespace}/${action.name}`" readonly />
        </NFormItem>
        <NFormItem label="Repository">
          <NInput v-model:value="repository" placeholder="repo name" />
        </NFormItem>
        <NFormItem label="Chart">
          <NInput v-model:value="chart" />
        </NFormItem>
        <NFormItem label="Version">
          <NInput v-model:value="version" />
        </NFormItem>
      </template>
      <template v-else>
        <NFormItem label="Release">
          <NInput :value="`${action.namespace}/${action.name}`" readonly />
        </NFormItem>
        <NFormItem label="Roll back to revision">
          <NInputNumber v-model:value="revision" :min="1" style="max-width: 160px" />
        </NFormItem>
      </template>

      <HelmValuesEditor
        v-if="action.mode !== 'rollback'"
        v-model:values-yaml="valuesYaml"
        v-model:saved-values="savedValues"
        :cluster="cluster"
        :action="action"
      />

      <HelmAdvancedOptions
        v-if="action.mode !== 'rollback'"
        v-model:no-hooks="noHooks"
        v-model:force="force"
        v-model:value-strategy="valueStrategy"
        v-model:max-history="maxHistory"
        v-model:description="description"
        :is-upgrade="action.mode === 'upgrade'"
      />
    </NForm>

    <div v-if="preview" class="helm-preview">
      <div class="preview-head">Rendered manifest (dry-run) — {{ preview.manifest ? '' : 'no manifest returned' }}</div>
      <YamlView v-if="preview.manifest" :text="preview.manifest" />
    </div>

    <template #footer>
      <div class="modal-actions">
        <NButton :disabled="busy" @click="emit('close')">Cancel</NButton>
        <NButton :disabled="busy" @click="run(true)">
          {{ busy ? 'Rendering…' : 'Preview (dry-run)' }}
        </NButton>
        <NButton type="primary" :disabled="busy || !preview" @click="run(false)">
          {{ applyLabel }}
        </NButton>
      </div>
    </template>
  </NModal>
</template>
