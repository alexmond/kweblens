<script setup lang="ts">
// The Form view inside the editor dialog. The YAML draft is the single source of truth:
// this parses it, lets you edit labels / annotations / data (ConfigMap & Secret) as
// key/value grids, and writes changes back into the YAML (preserving everything else), so
// the Editor tab, the diff and Apply all see one coherent document. Secret values are shown
// decoded and re-encoded (base64) on write-back.
import { NInput, NInputNumber, NSelect, NSwitch } from 'naive-ui';
import { computed, ref, watch } from 'vue';
import { parseDocument } from 'yaml';

import type { FormField } from '../schemaForm';
import { formFieldsFor, isCurated } from '../schemaForm';
import { initialRows } from '../textareaRows';
import KeyValueEditor from './KeyValueEditor.vue';

const model = defineModel<string>({ required: true });
// The kind's JSON Schema, from the cluster's OpenAPI — the same one that drives the editor's
// completion and lint, so the form and the YAML path cannot disagree about what is valid.
const props = defineProps<{ schema?: Record<string, unknown> | null }>();

const parseError = ref<string | null>(null);
const kind = ref('');
const isSecret = ref(false);
const hasData = ref(false);
const labels = ref<Record<string, string>>({});
const annotations = ref<Record<string, string>>({});
const data = ref<Record<string, string>>({});
const containers = ref<{ name?: string }[]>([]);
/** Current value per field path, read out of the YAML on every parse. */
const values = ref<Record<string, unknown>>({});

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

/**
 * Assign only when the value has actually changed, compared STRUCTURALLY.
 *
 * A ref assignment always triggers, because Vue compares with `Object.is` and a freshly built
 * object or array is never identical to the last one. So re-parsing the same document would
 * invalidate everything downstream for no reason — and where that "downstream" feeds back into
 * the parse, for no reason becomes forever (GH#334).
 *
 * Generic on purpose: the container list needs this exactly as much as the string maps do, and
 * two copies of the same four lines is how one of them ends up not getting the fix.
 */
const setIfChanged = <T,>(r: { value: T }, next: T) => {
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
    // Guarded like every other ref above, and for the same reason: an unguarded assignment
    // hands `fields` a new array on every parse even when nothing changed, so anything
    // downstream of `fields` re-runs for no reason. That is what made the watcher below able
    // to feed itself; the guard alone would not have fixed it, but leaving it unguarded is
    // what let one mistake become a hang.
    const cs = doc.getIn(['spec', 'template', 'spec', 'containers']);
    setIfChanged(containers, cs ? ((cs as { toJSON(): { name?: string }[] }).toJSON() ?? []) : []);
    const next: Record<string, unknown> = {};
    for (const field of fields.value) {
      const raw = doc.getIn(pathKeys(field.path));
      // Objects (an immutable selector) are shown as their YAML-ish text, not edited.
      next[field.path] =
        raw && typeof raw === 'object' && 'toJSON' in raw
          ? JSON.stringify((raw as { toJSON(): unknown }).toJSON())
          : (raw ?? null);
    }
    values.value = next;
  } catch (e) {
    parseError.value = String(e);
  }
};

/** Numeric segments address array indices; the yaml document wants them as numbers. */
const pathKeys = (path: string): (string | number)[] =>
  path.split('.').map((seg) => (/^\d+$/.test(seg) ? Number(seg) : seg));

const fields = computed<FormField[]>(() => formFieldsFor(kind.value, props.schema, containers.value));
const generated = computed(() => fields.value.filter((f) => !f.readOnly));
const immutable = computed(() => fields.value.filter((f) => f.readOnly));
const sectionTitle = computed(() => (isCurated(kind.value) ? kind.value : `${kind.value} (from the cluster's schema)`));

watch(model, parse, { immediate: true });
/**
 * Re-read once the schema arrives — it is fetched asynchronously, so the first parse runs
 * before the generated fields are known.
 *
 * **Watch the schema, not `fields`.** `fields` is computed over `containers`, and `parse`
 * ASSIGNS `containers` — so watching `fields` made the parse its own trigger: parse wrote
 * containers, fields recomputed to a new array, the watcher fired, parse ran again. Measured
 * at **401 parses for a single keystroke** (a counter capped at 400), against 1 at rest; the
 * renderer stopped yielding and the tab died with the edit unsaved and nothing reported
 * (GH#334). The schema is the actual dependency this was reaching for, and it cannot be
 * written by the thing it triggers.
 */
watch(
  () => props.schema,
  () => parse(model.value),
);

/**
 * Write one field back into the YAML.
 *
 * Only the edited node is touched: the document keeps every other field, its ordering and its
 * comments, so an edit shows up in the diff as exactly what changed. Re-serialising a parsed
 * object instead would silently drop whatever the form does not model — other controllers'
 * additions, defaulted values — which is the failure this avoids.
 */
const setField = (field: FormField, value: unknown) => {
  values.value = { ...values.value, [field.path]: value };
  try {
    const doc = parseDocument(model.value);
    if (doc.errors.length) {
      return;
    }
    const keys = pathKeys(field.path);
    if (value === null || value === '' || value === undefined) {
      doc.deleteIn(keys);
    } else {
      doc.setIn(keys, value);
    }
    model.value = String(doc);
  } catch {
    // keep the last good document
  }
};

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
      <section v-if="generated.length || immutable.length" class="form-section">
        <h4>{{ sectionTitle }}</h4>
        <!-- Types, options and help text all come from the cluster's own schema, so the form
             offers exactly what the API server accepts. -->
        <div class="form-grid">
          <label v-for="f in generated" :key="f.path" class="form-row">
            <span class="form-label">
              {{ f.label }}
              <span v-if="f.description" class="form-hint">{{ f.description }}</span>
            </span>
            <NSwitch
              v-if="f.type === 'boolean'"
              :value="values[f.path] === true"
              @update:value="(v) => setField(f, v)"
            />
            <NInputNumber
              v-else-if="f.type === 'integer'"
              :value="(values[f.path] as number) ?? null"
              size="small"
              @update:value="(v) => setField(f, v)"
            />
            <NSelect
              v-else-if="f.type === 'enum'"
              :value="(values[f.path] as string) ?? null"
              :options="(f.options ?? []).map((o) => ({ label: o, value: o }))"
              size="small"
              clearable
              @update:value="(v) => setField(f, v)"
            />
            <!-- A schema string field is a textarea, not an input. These come from the
                 cluster's own OpenAPI, so a CRD is free to declare a string that holds a
                 script, a PEM block or an embedded config file, and there is no list of which
                 ones do — a single-line box is a guess that only ever fails one way. At one
                 row it looks and behaves like the input it replaces. -->
            <NInput
              v-else
              :value="(values[f.path] as string) ?? ''"
              type="textarea"
              :rows="initialRows((values[f.path] as string) ?? '', 1, 12)"
              size="small"
              @update:value="(v) => setField(f, v)"
            />
          </label>
          <!-- Shown, but not offered: an immutable field the API server would reject is
               better displayed with its reason than presented as editable. -->
          <label v-for="f in immutable" :key="f.path" class="form-row">
            <span class="form-label">
              {{ f.label }}
              <span class="form-hint">{{ f.readOnlyReason }}</span>
            </span>
            <!-- Readonly, and still a resizable textarea: an immutable value the reader cannot
                 change is one they can only READ, and a disabled single-line input is the worst
                 possible way to read a long one — no wrap, no selection, and the caret keys a
                 disabled control ignores. Nothing here is editable, so this costs nothing. -->
            <NInput
              :value="String(values[f.path] ?? '')"
              type="textarea"
              :rows="initialRows(String(values[f.path] ?? ''), 1, 12)"
              size="small"
              readonly
              disabled
            />
          </label>
        </div>
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
