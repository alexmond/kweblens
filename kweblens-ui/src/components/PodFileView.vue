<script setup lang="ts">
// One file inside a container: view, download, edit and delete.
//
// Three rules this pane exists to keep:
//   * `editable` is the SERVER's verdict and is never second-guessed. A binary and a file
//     that did not arrive whole are both non-editable, because saving either would rewrite
//     it with a mis-decoded or partial copy of itself.
//   * a refused file (too large) is not shown as content at all — the server declined to
//     hand back a truncated copy, and the UI must not imply it has one.
//   * writing is a SEPARATE gate (kweblens.files.writable) with no pre-flight, so Save and
//     Delete are offered optimistically and withdrawn the moment a write comes back
//     `files-read-only`.
//
// Emits: deleted () — the file is gone, the listing must refetch
//        auth-required () — the request came back 401; the shell prompts for the login
import { NPopconfirm } from 'naive-ui';
import { computed, onMounted, ref, shallowRef, watch } from 'vue';

import { podFiles } from '../api';
import { filesFeature, formatSize, isAuthFailure, noticeFor } from '../podFiles';
import type { FileDirection, FileNotice as FileNoticeShape } from '../podFiles';
import type { PodFileContent } from '../types';
import FileNotice from './FileNotice.vue';

const props = defineProps<{ cluster: string; namespace: string; pod: string; container: string; path: string }>();
const emit = defineEmits<{ (e: 'deleted'): void; (e: 'auth-required'): void }>();

const content = shallowRef<PodFileContent | null>(null);
const notice = ref<FileNoticeShape | null>(null);
const loading = ref(false);
const draft = ref<string | null>(null);
const busy = ref(false);
const saved = ref(false);

const name = computed(() => props.path.slice(props.path.lastIndexOf('/') + 1));
/** Identifies the request in flight, so a slow answer for a file you have left is dropped. */
const requestKey = () => `${props.container}:${props.path}`;
/** Editing needs the server's verdict AND the write gate — either one can withdraw it. */
const canEdit = computed(() => content.value?.editable === true && filesFeature.writable.value);

function fail(e: unknown, direction: FileDirection = 'read') {
  filesFeature.noteFailure(e);
  notice.value = noticeFor(e, direction);
  if (isAuthFailure(e)) {
    emit('auth-required');
  }
}

/**
 * (Re)read the file. `keepSaved` exists because a save ends by reloading, and clearing the
 * confirmation on the way would mean "Saved." is set and unset in the same tick — it never
 * reached the screen at all, which is how it went un-measured for contrast until now.
 */
function load(keepSaved = false) {
  loading.value = true;
  notice.value = null;
  draft.value = null;
  if (!keepSaved) {
    saved.value = false;
  }
  const mine = requestKey();
  podFiles
    .read(props.cluster, props.namespace, props.pod, props.container, props.path)
    .then((c) => {
      if (mine !== requestKey()) {
        return;
      }
      filesFeature.noteReached();
      content.value = c;
    })
    .catch((e: unknown) => {
      if (mine !== requestKey()) {
        return;
      }
      content.value = null;
      fail(e);
    })
    .finally(() => (loading.value = false));
}

// Wrapped rather than passed straight in: a watch handler is called with (new, old), which
// would arrive as `keepSaved` and keep a stale confirmation across a change of file.
watch(
  () => [props.cluster, props.namespace, props.pod, props.container, props.path],
  () => load(),
  {
    immediate: true,
  },
);

// The listing above can be hundreds of rows; without this, clicking a file appends a pane
// below the fold and reads as "nothing happened".
const host = ref<HTMLElement | null>(null);
onMounted(() => host.value?.scrollIntoView({ block: 'nearest' }));

function download() {
  busy.value = true;
  notice.value = null;
  podFiles
    .download(props.cluster, props.namespace, props.pod, props.container, props.path)
    .then((blob) => {
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = name.value || 'download';
      a.click();
      URL.revokeObjectURL(url);
    })
    .catch(fail)
    .finally(() => (busy.value = false));
}

function save() {
  const text = draft.value;
  if (text === null) {
    return;
  }
  busy.value = true;
  notice.value = null;
  podFiles
    .writeText(props.cluster, props.namespace, props.pod, props.container, props.path, text)
    .then(() => {
      saved.value = true;
      // Reload so what is shown is what the container now holds, not what was typed —
      // keeping the confirmation, which the reload would otherwise clear immediately.
      load(true);
    })
    .catch((e: unknown) => fail(e, 'write'))
    .finally(() => (busy.value = false));
}

function remove() {
  busy.value = true;
  notice.value = null;
  podFiles
    .remove(props.cluster, props.namespace, props.pod, props.container, props.path)
    .then(() => emit('deleted'))
    .catch((e: unknown) => fail(e, 'write'))
    .finally(() => (busy.value = false));
}
</script>

<template>
  <section ref="host" class="files-view">
    <header class="files-view-head">
      <span class="files-view-name mono">{{ name }}</span>
      <span v-if="content" class="files-view-meta">{{ formatSize(content.size) }}</span>
      <span v-if="content?.binary" class="files-tag">binary</span>
      <span v-if="content && !content.editable && !content.binary" class="files-tag">read-only copy</span>
      <span class="files-spacer" />
      <button type="button" class="btn" :disabled="busy || loading" @click="download()">Download</button>
      <button
        v-if="canEdit && draft === null"
        type="button"
        class="btn"
        :disabled="busy"
        @click="draft = content?.content ?? ''"
      >
        Edit
      </button>
      <template v-if="draft !== null">
        <button type="button" class="btn primary" :disabled="busy" @click="save()">Save</button>
        <button type="button" class="btn" :disabled="busy" @click="draft = null">Cancel</button>
      </template>
      <NPopconfirm v-if="filesFeature.writable.value" @positive-click="remove()">
        <template #trigger>
          <button type="button" class="btn danger" :disabled="busy">Delete</button>
        </template>
        Delete {{ name }} from this container? It cannot be undone.
      </NPopconfirm>
    </header>

    <FileNotice v-if="notice" :notice="notice" :retrying="loading" @retry="load()" />
    <p v-if="saved" class="files-saved">Saved.</p>

    <p v-if="loading" class="empty">Loading…</p>
    <template v-else-if="content">
      <p v-if="content.binary" class="files-hint">
        Binary file — not text, so kweblens will not open it in an editor where saving would corrupt it. Download it to
        inspect it.
      </p>
      <p v-else-if="content.truncated" class="files-hint">
        Only part of this file arrived, so it is shown for reading but cannot be saved — a save would replace the whole
        file with this fragment.
      </p>
      <textarea v-if="draft !== null" v-model="draft" class="files-editor mono" spellcheck="false"></textarea>
      <pre v-else-if="!content.binary" class="files-content mono">{{ content.content }}</pre>
    </template>
  </section>
</template>
