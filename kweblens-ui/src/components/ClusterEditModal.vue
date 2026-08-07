<script setup lang="ts">
// Add or edit a runtime cluster.
//
// The kubeconfig is a CREDENTIAL, and this is the only place in the UI that handles one:
//   - It travels one way. The server never returns it, so on edit the field starts empty
//     and an empty field means "keep the stored one" rather than "clear it".
//   - It is never persisted client-side — no localStorage, no prefs, no query string. It
//     lives in this component's state for as long as the modal is open and goes away with it.
//   - "Read contexts" posts it to an endpoint that parses and discards it, so the user can
//     pick a context without the credential being stored first.
//
// Emits: saved (), cancel ()
import { NButton, NForm, NFormItem, NInput, NModal, NSelect } from 'naive-ui';
import { computed, ref } from 'vue';

import { api } from '../api';
import { failureNotice } from '../apiFailure';
import { idProblem } from '../clustersPage';
import type { ClusterDefinition } from '../types';

const props = defineProps<{ definition: ClusterDefinition; isNew: boolean; existingIds: string[] }>();
const emit = defineEmits<{ (e: 'saved'): void; (e: 'cancel'): void }>();

const id = ref(props.definition.id);
const name = ref(props.definition.name);
const context = ref<string | null>(props.definition.context);
const kubeconfig = ref('');
const contexts = ref<string[]>([]);
const busy = ref(false);
const reading = ref(false);
const error = ref<string | null>(null);

// On add the id must be new; on edit it is fixed, so it is not re-validated against the
// list (it is legitimately already in it).
const idError = computed(() => (props.isNew ? idProblem(id.value, props.existingIds) : null));

const canSave = computed(
  () => !idError.value && name.value.trim().length > 0 && (!props.isNew || kubeconfig.value.trim().length > 0),
);

const readContexts = () => {
  reading.value = true;
  error.value = null;
  api
    .kubeconfigContexts(kubeconfig.value)
    .then((list) => {
      contexts.value = list;
      // A single-context kubeconfig has only one sensible answer; choosing it saves a step
      // and removes the chance of leaving it unset by accident.
      if (list.length === 1) {
        context.value = list[0];
      }
    })
    .catch((e: unknown) => (error.value = failureNotice(e)))
    .finally(() => (reading.value = false));
};

const save = () => {
  busy.value = true;
  error.value = null;
  const body: ClusterDefinition = {
    id: id.value.trim(),
    name: name.value.trim(),
    context: context.value,
  };
  // Only send the kubeconfig when one was actually typed. On edit that means the stored
  // credential is left alone rather than overwritten with an empty string.
  if (kubeconfig.value.trim()) {
    body.kubeconfig = kubeconfig.value;
  }
  const request = props.isNew ? api.addCluster(body) : api.updateCluster(props.definition.id, body);
  request
    .then(() => {
      // Drop the credential from memory as soon as it has been accepted.
      kubeconfig.value = '';
      emit('saved');
    })
    .catch((e: unknown) => (error.value = failureNotice(e)))
    .finally(() => (busy.value = false));
};

const contextOptions = computed(() => contexts.value.map((c) => ({ label: c, value: c })));
</script>

<template>
  <NModal
    :show="true"
    preset="card"
    :title="isNew ? 'Add cluster' : `Edit ${definition.name}`"
    style="width: 620px"
    @update:show="(v: boolean) => !v && emit('cancel')"
  >
    <NForm label-placement="top">
      <NFormItem label="Id" :validation-status="idError ? 'error' : undefined" :feedback="idError ?? ''">
        <NInput v-model:value="id" :disabled="!isNew" placeholder="prod-eu" />
      </NFormItem>

      <NFormItem label="Name">
        <NInput v-model:value="name" placeholder="Production EU" />
      </NFormItem>

      <NFormItem :label="isNew ? 'Kubeconfig' : 'Kubeconfig (leave blank to keep the stored one)'">
        <NInput
          v-model:value="kubeconfig"
          type="textarea"
          :rows="8"
          placeholder="Paste the kubeconfig YAML"
          :input-props="{ spellcheck: false }"
        />
      </NFormItem>

      <p class="cem-note">
        Stored server-side as a credential and never sent back to the browser. It must embed its credentials — a
        kubeconfig referring to certificate files by path has nothing to resolve those paths against here.
      </p>

      <NFormItem label="Context">
        <div class="cem-context">
          <NSelect
            v-model:value="context"
            :options="contextOptions"
            :disabled="!contexts.length"
            :placeholder="contexts.length ? 'Select a context' : 'Read the kubeconfig to list its contexts'"
            clearable
          />
          <NButton :disabled="!kubeconfig.trim()" :loading="reading" @click="readContexts">Read contexts</NButton>
        </div>
      </NFormItem>

      <p v-if="error" class="cem-error" role="alert">{{ error }}</p>
    </NForm>

    <template #footer>
      <div class="cem-footer">
        <NButton @click="emit('cancel')">Cancel</NButton>
        <NButton type="primary" :disabled="!canSave" :loading="busy" @click="save">
          {{ isNew ? 'Add' : 'Save' }}
        </NButton>
      </div>
    </template>
  </NModal>
</template>
