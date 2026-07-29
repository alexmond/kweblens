<script setup lang="ts">
// The Form view inside the editor dialog. The YAML draft is the single source of truth:
// this parses it, lets you edit labels / annotations / data (ConfigMap & Secret) as
// key/value grids, and writes changes back into the YAML (preserving everything else), so
// the Editor tab, the diff and Apply all see one coherent document. Secret values are shown
// decoded and re-encoded (base64) on write-back.
import { ref, watch } from 'vue';
import { parseDocument } from 'yaml';

import KeyValueEditor from './KeyValueEditor.vue';

const model = defineModel<string>({ required: true });

const parseError = ref<string | null>(null);
const kind = ref('');
const isSecret = ref(false);
const hasData = ref(false);
const labels = ref<Record<string, string>>({});
const annotations = ref<Record<string, string>>({});
const data = ref<Record<string, string>>({});

const decodeB64 = (b64: string): string => {
  try {
    return new TextDecoder().decode(Uint8Array.from(atob(b64), (c) => c.charCodeAt(0)));
  } catch {
    return b64;
  }
};
const encodeB64 = (s: string): string => btoa(String.fromCharCode(...new TextEncoder().encode(s)));
const mapValues = (m: Record<string, string>, f: (v: string) => string): Record<string, string> =>
  Object.fromEntries(Object.entries(m).map(([k, v]) => [k, f(v)]));

const setIfChanged = (r: { value: Record<string, string> }, next: Record<string, string>) => {
  if (JSON.stringify(r.value) !== JSON.stringify(next)) {
    r.value = next;
  }
};

// Parse the incoming YAML into the form fields. Only overwrites a field when it actually
// differs, so our own write-back (which re-emits the YAML) doesn't reset an in-progress edit.
const parse = (text: string) => {
  try {
    const doc = parseDocument(text);
    if (doc.errors.length) {
      parseError.value = doc.errors[0].message;
      return;
    }
    parseError.value = null;
    kind.value = String(doc.get('kind') ?? '');
    isSecret.value = kind.value === 'Secret';
    hasData.value = kind.value === 'ConfigMap' || isSecret.value;
    const l = doc.getIn(['metadata', 'labels']);
    setIfChanged(labels, l ? (l as { toJSON(): Record<string, string> }).toJSON() : {});
    const a = doc.getIn(['metadata', 'annotations']);
    setIfChanged(annotations, a ? (a as { toJSON(): Record<string, string> }).toJSON() : {});
    if (hasData.value) {
      const d = doc.getIn(['data']);
      const raw = d ? (d as { toJSON(): Record<string, string> }).toJSON() : {};
      setIfChanged(data, isSecret.value ? mapValues(raw, decodeB64) : raw);
    }
  } catch (e) {
    parseError.value = String(e);
  }
};

watch(model, parse, { immediate: true });

// Set a map at a path (deleting the key when empty), then re-serialise and emit.
const setMap = (doc: ReturnType<typeof parseDocument>, path: string[], obj: Record<string, string>) => {
  if (Object.keys(obj).length === 0) {
    doc.deleteIn(path);
  } else {
    doc.setIn(path, obj);
  }
};
// Write back only the section the user just edited, so untouched sections keep their
// original formatting (and don't show up as spurious changes in the diff).
const writeBack = (which: 'labels' | 'annotations' | 'data') => {
  try {
    const doc = parseDocument(model.value);
    if (doc.errors.length) {
      return;
    }
    if (which === 'labels') {
      setMap(doc, ['metadata', 'labels'], labels.value);
    } else if (which === 'annotations') {
      setMap(doc, ['metadata', 'annotations'], annotations.value);
    } else {
      setMap(doc, ['data'], isSecret.value ? mapValues(data.value, encodeB64) : data.value);
    }
    model.value = String(doc);
  } catch {
    // keep the last good document
  }
};

const onLabels = (v: Record<string, string>) => {
  labels.value = v;
  writeBack('labels');
};
const onAnnotations = (v: Record<string, string>) => {
  annotations.value = v;
  writeBack('annotations');
};
const onData = (v: Record<string, string>) => {
  data.value = v;
  writeBack('data');
};
</script>

<template>
  <div class="form-fields">
    <div v-if="parseError" class="form-parse-error">
      Can't parse the YAML — fix it in the Editor tab to use the form.
    </div>
    <template v-else>
      <section class="form-section">
        <h4>Labels</h4>
        <KeyValueEditor
          :model-value="labels"
          key-placeholder="label"
          value-placeholder="value"
          @update:model-value="onLabels"
        />
      </section>
      <section class="form-section">
        <h4>Annotations</h4>
        <KeyValueEditor
          :model-value="annotations"
          key-placeholder="annotation"
          value-placeholder="value"
          @update:model-value="onAnnotations"
        />
      </section>
      <section v-if="hasData" class="form-section">
        <h4>{{ isSecret ? 'Data — values shown decoded' : 'Data' }}</h4>
        <KeyValueEditor
          :model-value="data"
          key-placeholder="key"
          value-placeholder="value"
          :secret="isSecret"
          @update:model-value="onData"
        />
      </section>
    </template>
  </div>
</template>
