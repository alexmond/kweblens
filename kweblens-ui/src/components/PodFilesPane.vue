<script setup lang="ts">
// The Files tab of a pod: browse a container's filesystem, then view/edit one file.
//
// Availability cannot be pre-flighted — there is no endpoint that reports
// `kweblens.files.enabled`, and every files endpoint needs a pod — so it is learned from
// the first listing and cached in `filesFeature` for the page session. A `files-disabled`
// answer is therefore shown once, in full, as an explained state (not an error), and from
// then on the tab is not offered at all (see Detail.vue).
//
// Reads here are authenticated even in open-mode, on purpose: a container's disk includes
// mounted Secrets and the service-account token. Signed out, the pane asks for the login
// rather than firing a request that can only 401.
//
// Emits: auth-required () — the shell opens the login modal.
import { computed, ref, shallowRef, watch } from 'vue';

import { podFiles } from '../api';
import {
  breadcrumbs,
  containerChoices,
  fileFromDrop,
  filesFeature,
  formatMode,
  formatModified,
  formatSize,
  isDirectory,
  isReadable,
  joinPath,
  noticeFor,
  parentPath,
  isAuthFailure,
  readUpload,
  startPath,
  uploadConflict,
  withinRoots,
} from '../podFiles';
import type { FileDirection, FileNotice as FileNoticeShape, UploadPlan } from '../podFiles';
import { directoryEmpty } from '../emptyState';
import type { KubeObject, PodDirectoryListing, PodFileEntry } from '../types';
import EmptyState from './EmptyState.vue';
import FileNotice from './FileNotice.vue';
import LoadingNotice from './LoadingNotice.vue';
import PodFileView from './PodFileView.vue';

const props = defineProps<{ cluster: string; namespace: string; pod: string; obj: KubeObject; authed: boolean }>();
const emit = defineEmits<{ (e: 'auth-required'): void }>();

const containers = computed(() => containerChoices(props.obj));
const container = ref(containers.value.find((c) => !c.init)?.name ?? containers.value[0]?.name ?? '');
// Confined deployments (kweblens.files.allowed-roots) open at the first root: `/` is a
// path such a server refuses, so opening there greeted every reader with a refusal for a
// directory they never asked for. Unconfined this is still `/`.
const roots = computed(() => filesFeature.allowedRoots.value);
const path = ref(startPath(filesFeature.allowedRoots.value));
const listing = shallowRef<PodDirectoryListing | null>(null);
const notice = ref<FileNoticeShape | null>(null);
const loading = ref(false);
const selected = ref<string | null>(null);

// The listing shown is `listing.path`, not `path` — the reader can already have clicked on
// into a directory whose answer has not arrived, and naming the one they are about to leave
// would put the wrong path in the sentence.
const emptyCopy = computed(() =>
  directoryEmpty({
    loading: loading.value,
    failed: notice.value !== null,
    count: listing.value?.entries.length ?? 0,
    path: listing.value?.path ?? path.value,
  }),
);

// Upload state. `pending` holds a read-and-checked file waiting for the "replace what is
// already there?" answer — an upload that silently overwrites is the one destructive thing
// this pane can do without the reader naming a file first.
const picker = ref<HTMLInputElement | null>(null);
const pending = ref<UploadPlan | null>(null);
const uploading = ref(false);
const uploaded = ref<string | null>(null);
const dragging = ref(false);
/** Upload is offered only where a write can actually succeed (kweblens.files.writable). */
const canUpload = computed(() => filesFeature.writable.value);

// A crumb above the configured roots is shown but not offered: it is part of the path,
// and hiding it would misrepresent where you are, but clicking it could only ever 403.
const crumbs = computed(() =>
  breadcrumbs(path.value).map((c) => ({ ...c, reachable: withinRoots(c.path, roots.value) })),
);
/** Identifies the request in flight, so a slow answer for a directory you have left is dropped. */
const requestKey = () => `${container.value}:${path.value}`;
const entryName = (e: PodFileEntry) => (e.linkTarget ? `${e.name} → ${e.linkTarget}` : e.name);
const entryKind = (e: PodFileEntry) => (e.type === 'symlink' ? `link (${e.linkType ?? 'broken'})` : e.type);

function load() {
  if (!props.authed) {
    return;
  }
  loading.value = true;
  notice.value = null;
  const mine = requestKey();
  podFiles
    .list(props.cluster, props.namespace, props.pod, container.value, path.value)
    .then((l) => {
      if (mine !== requestKey()) {
        return;
      }
      filesFeature.noteReached();
      listing.value = l;
    })
    .catch((e: unknown) => {
      if (mine !== requestKey()) {
        return;
      }
      // The listing is deliberately cleared: showing the previous directory's entries under
      // a message about this one would be a claim about the wrong directory.
      listing.value = null;
      fail(e);
    })
    .finally(() => (loading.value = false));
}

function fail(e: unknown, direction: FileDirection = 'read') {
  filesFeature.noteFailure(e);
  notice.value = noticeFor(e, direction);
  if (isAuthFailure(e)) {
    emit('auth-required');
  }
}

watch(
  () => [props.cluster, props.namespace, props.pod, container.value, path.value, props.authed],
  () => {
    // A confirmation naming a file belongs to the directory it was written in, and a
    // half-answered overwrite question must not follow the reader somewhere else.
    uploaded.value = null;
    pending.value = null;
    load();
  },
  { immediate: true },
);

function open(entry: PodFileEntry) {
  const full = joinPath(path.value, entry.name);
  if (isDirectory(entry)) {
    selected.value = null;
    path.value = full;
  } else if (isReadable(entry)) {
    selected.value = full;
  }
}

/** True while the parent is a directory this deployment would actually serve. */
const canGoUp = computed(() => path.value !== '/' && withinRoots(parentPath(path.value), roots.value));

function goUp() {
  if (!canGoUp.value) {
    return;
  }
  selected.value = null;
  path.value = parentPath(path.value);
}

/** A delete removes the open file: drop the viewer and refetch the directory. */
function onDeleted() {
  selected.value = null;
  load();
}

// --- upload ---------------------------------------------------------------------------
//
// One file at a time, into the directory on screen, as base64 through the same write
// endpoint the editor uses. There is no separate upload API and no separate cap: the
// server enforces kweblens.files.max-write-bytes either way, and an oversized file is
// REFUSED, never truncated — a partial upload would replace a container's file with a
// fragment of the one that was picked.

/** Take a picked or dropped file: read it, then either ask or send. */
function offer(file: File) {
  notice.value = null;
  uploaded.value = null;
  pending.value = null;
  uploading.value = true;
  readUpload(path.value, file, filesFeature.writeLimit.value)
    .then((plan) => {
      const clash = uploadConflict(listing.value, plan.name);
      if (clash) {
        pending.value = plan;
        return undefined;
      }
      return send(plan);
    })
    .catch((e: unknown) => fail(e, 'write'))
    .finally(() => (uploading.value = false));
}

/** PUT the prepared bytes, then refetch so the listing shows what the container now holds. */
function send(plan: UploadPlan): Promise<void> {
  uploading.value = true;
  return podFiles
    .writeBase64(props.cluster, props.namespace, props.pod, container.value, plan.path, plan.base64)
    .then(() => {
      filesFeature.noteReached();
      pending.value = null;
      uploaded.value = plan.name;
      load();
    })
    .catch((e: unknown) => fail(e, 'write'))
    .finally(() => (uploading.value = false));
}

function onPicked(event: Event) {
  const input = event.target as HTMLInputElement;
  const file = input.files?.[0];
  if (file) {
    offer(file);
  }
  // Cleared so picking the same file twice fires `change` again.
  input.value = '';
}

function onDrop(event: DragEvent) {
  dragging.value = false;
  if (!canUpload.value) {
    return;
  }
  // `fileFromDrop` refuses a folder or a multi-file drop with its own explained notice; a
  // drop carrying nothing usable (dragged text, a link) is ignored rather than reported,
  // because nothing was asked for.
  try {
    const file = fileFromDrop(event.dataTransfer);
    if (file) {
      offer(file);
    }
  } catch (e: unknown) {
    fail(e, 'write');
  }
}
</script>

<template>
  <div
    class="files-pane"
    :class="{ dragging: dragging && canUpload }"
    @dragover.prevent="dragging = canUpload"
    @dragleave="dragging = false"
    @drop.prevent="onDrop($event)"
  >
    <div v-if="!authed" class="files-notice off" role="status">
      <p class="files-notice-title">Sign in to browse files</p>
      <p class="files-notice-detail">
        Reading a container’s filesystem needs the admin login even when kweblens is in open mode — a pod’s disk holds
        mounted Secrets and its service-account token.
      </p>
      <button type="button" class="btn" @click="emit('auth-required')">Sign in</button>
    </div>

    <template v-else>
      <header class="files-head">
        <label class="files-field">
          <span>Container</span>
          <select v-model="container" class="files-select">
            <option v-for="c in containers" :key="c.name" :value="c.name">
              {{ c.init ? `${c.name} (init)` : c.name }}
            </option>
          </select>
        </label>
        <span class="files-spacer" />
        <template v-if="canUpload">
          <input ref="picker" type="file" class="files-picker" @change="onPicked($event)" />
          <button type="button" class="btn" :disabled="loading || uploading" @click="picker?.click()">
            {{ uploading ? 'Uploading…' : 'Upload' }}
          </button>
        </template>
        <button type="button" class="btn" :disabled="loading || !canGoUp" @click="goUp()">Up</button>
        <button type="button" class="btn" :disabled="loading" @click="load()">Refresh</button>
      </header>

      <nav class="files-crumbs" aria-label="Path">
        <template v-for="(c, i) in crumbs" :key="c.path">
          <span v-if="i > 0" class="files-crumb-sep">/</span>
          <button
            type="button"
            class="files-crumb"
            :class="{ current: c.path === path }"
            :disabled="!c.reachable"
            :title="c.reachable ? undefined : 'Outside kweblens.files.allowed-roots'"
            @click="
              selected = null;
              path = c.path;
            "
          >
            {{ c.label }}
          </button>
        </template>
      </nav>

      <FileNotice v-if="notice" :notice="notice" :retrying="loading" @retry="load()" />

      <div v-if="pending" class="files-confirm" role="alert">
        <p>
          <span class="mono">{{ pending.name }}</span> already exists in this directory. Uploading replaces its contents
          — this cannot be undone.
        </p>
        <div class="files-confirm-actions">
          <button type="button" class="btn danger" :disabled="uploading" @click="send(pending)">Replace</button>
          <button type="button" class="btn" :disabled="uploading" @click="pending = null">Cancel</button>
        </div>
      </div>
      <p v-else-if="uploaded" class="files-saved">Uploaded {{ uploaded }}.</p>
      <p v-if="dragging && canUpload" class="files-hint">Drop to upload into {{ path }}.</p>

      <LoadingNotice v-if="loading && !listing" />
      <template v-else-if="listing">
        <p v-if="listing.resolvedPath && listing.resolvedPath !== listing.path" class="files-hint">
          Resolves to <span class="mono">{{ listing.resolvedPath }}</span> inside the container.
        </p>
        <p v-if="listing.truncated" class="files-hint">
          This directory holds more entries than are listed (kweblens.files.max-entries).
        </p>
        <EmptyState v-if="emptyCopy" :title="emptyCopy.title" :body="emptyCopy.body" variant="inline" />
        <div v-else-if="listing.entries.length > 0" class="files-list">
          <table class="mini files-table">
            <thead>
              <tr>
                <th>Name</th>
                <th>Type</th>
                <th>Size</th>
                <th>Mode</th>
                <th>Owner</th>
                <th>Modified</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="e in listing.entries" :key="e.name" :class="{ selected: selected === joinPath(path, e.name) }">
                <td>
                  <button
                    v-if="isDirectory(e) || isReadable(e)"
                    type="button"
                    class="files-entry"
                    :class="{ dir: isDirectory(e) }"
                    @click="open(e)"
                  >
                    {{ entryName(e) }}
                  </button>
                  <span v-else class="files-entry-plain">{{ entryName(e) }}</span>
                </td>
                <td>{{ entryKind(e) }}</td>
                <td>{{ isDirectory(e) ? '—' : formatSize(e.size) }}</td>
                <td class="mono">{{ formatMode(e.mode) }}</td>
                <td>{{ e.owner ?? '—' }}</td>
                <td>{{ formatModified(e.modified) }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </template>

      <PodFileView
        v-if="selected"
        :key="selected + container"
        :cluster="cluster"
        :namespace="namespace"
        :pod="pod"
        :container="container"
        :path="selected"
        @deleted="onDeleted()"
        @auth-required="emit('auth-required')"
      />
    </template>
  </div>
</template>
