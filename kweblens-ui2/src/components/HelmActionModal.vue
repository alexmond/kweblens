<script setup lang="ts">
import { onMounted, ref } from 'vue';

import { ApiError, api } from '../api';
import { useEscapeKey } from '../composables/useEscapeKey';
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
const savedValues = ref<string[]>([]);
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

useEscapeKey(() => emit('close'));

const title =
  props.action.mode === 'install'
    ? 'Install chart'
    : props.action.mode === 'upgrade'
      ? 'Upgrade release'
      : 'Rollback release';

const applyLabel =
  props.action.mode === 'install' ? 'Install' : props.action.mode === 'upgrade' ? 'Upgrade' : 'Rollback';

const clampRevision = (e: Event) => {
  const raw = (e.target as HTMLInputElement).value || '1';
  revision.value = Math.max(1, Number.parseInt(raw, 10));
};

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
      if (err instanceof ApiError && err.status === 401) {
        emit('auth-expired');
      }
      error.value = String(err);
    })
    .finally(() => (busy.value = false));
};
</script>

<template>
  <div class="modal-backdrop" @click="emit('close')">
    <form class="modal wide" @click.stop @submit.prevent>
      <h2>{{ title }}</h2>
      <p class="modal-note">Preview a dry-run first; Apply is enabled once the render succeeds.</p>
      <div v-if="error" class="error">{{ error }}</div>

      <template v-if="action.mode === 'install'">
        <label><span>Chart</span><input :value="`${repository}/${chart}`" readonly /></label>
        <label><span>Version</span><input v-model="version" /></label>
        <label><span>Release name</span><input v-model="releaseName" /></label>
        <label><span>Namespace</span><input v-model="namespace" /></label>
        <label class="check">
          <input v-model="createNamespace" type="checkbox" />
          <span>Create namespace if missing</span>
        </label>
      </template>
      <template v-else-if="action.mode === 'upgrade'">
        <label><span>Release</span><input :value="`${action.namespace}/${action.name}`" readonly /></label>
        <label><span>Repository</span><input v-model="repository" placeholder="repo name" /></label>
        <label><span>Chart</span><input v-model="chart" /></label>
        <label><span>Version</span><input v-model="version" /></label>
      </template>
      <template v-else>
        <label><span>Release</span><input :value="`${action.namespace}/${action.name}`" readonly /></label>
        <label>
          <span>Roll back to revision</span>
          <input type="number" :min="1" :value="revision" @input="clampRevision" />
        </label>
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

      <div v-if="preview" class="helm-preview">
        <div class="preview-head">
          Rendered manifest (dry-run) — {{ preview.manifest ? '' : 'no manifest returned' }}
        </div>
        <YamlView v-if="preview.manifest" :text="preview.manifest" />
      </div>

      <div class="modal-actions">
        <button type="button" class="btn" :disabled="busy" @click="emit('close')">Cancel</button>
        <button type="button" class="btn" :disabled="busy" @click="run(true)">
          {{ busy ? 'Rendering…' : 'Preview (dry-run)' }}
        </button>
        <button type="button" class="btn primary" :disabled="busy || !preview" @click="run(false)">
          {{ applyLabel }}
        </button>
      </div>
    </form>
  </div>
</template>
