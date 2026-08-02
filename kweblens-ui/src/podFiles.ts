import { ref } from 'vue';

import { objSpec } from './kube';
import type { KubeObject, PodDirectoryListing, PodFileEntry } from './types';

/**
 * Logic for the pod file browser (GH#140) — everything except the rendering, so it can be
 * tested without a DOM.
 *
 * <p>The backend (`web/files/`) went to real trouble to give every failure its own stable
 * `code`: "this image has no shell" is a different fact from "this container is not
 * running", which is different again from "the whole feature is switched off". Collapsing
 * those into one red banner would throw away the only information the reader needs, so
 * every code is mapped here to a title, the server's own sentence, and — where there is
 * one — the action that resolves it.
 */

/** A failure from the file API, carrying the server's `ProblemDetail` code. */
export class PodFileError extends Error {
  readonly status: number;

  /** The stable `code` from the ProblemDetail body, or `''` when the body carried none. */
  readonly code: string;

  constructor(status: number, code: string, detail: string) {
    super(detail);
    this.name = 'PodFileError';
    this.status = status;
    this.code = code;
  }
}

/**
 * How a failure should read. `off` and `blocked` are deliberately NOT `error`: a feature
 * that is switched off, and a container that ships no shell, are both correct states of
 * the system rather than malfunctions, and shouting at the reader in red would be a lie
 * about what happened.
 */
type NoticeTone = 'off' | 'blocked' | 'error';

export interface FileNotice {
  tone: NoticeTone;
  title: string;
  /** The server's own sentence — it names the path/container and is worth keeping. */
  detail: string;
  /** What to do about it, when there is something. */
  hint: string | null;
  /** Whether trying the same request again could plausibly succeed. */
  retryable: boolean;
}

interface NoticeShape {
  tone: NoticeTone;
  title: string;
  hint?: string;
  /**
   * Replaces `hint` when the failure happened on the way IN (a save or an upload). Only a
   * few codes need it, but for those the read hint is actively misleading — telling someone
   * whose upload was refused to raise `max-read-bytes` sends them to the wrong setting.
   */
  writeHint?: string;
  retryable?: boolean;
}

/** Which direction a failed request was going; it changes the advice, never the fact. */
export type FileDirection = 'read' | 'write';

/**
 * One entry per code the server can send. Anything not listed falls through to a generic
 * shape, which is why the table is exhaustive rather than "the interesting ones".
 */
const NOTICES: Record<string, NoticeShape> = {
  'files-disabled': {
    tone: 'off',
    title: 'The file browser is switched off',
    hint:
      'Start kweblens with kweblens.files.enabled=true (KWEBLENS_FILES_ENABLED=true) to turn it on. ' +
      'It is off by default because a container’s disk includes mounted Secrets and the ' +
      'service-account token, which no other kweblens view exposes.',
  },
  'files-read-only': {
    tone: 'off',
    title: 'This instance is browse-only',
    hint: 'Files can be viewed and downloaded but not changed (kweblens.files.writable=false).',
  },
  'no-shell': {
    tone: 'blocked',
    title: 'This container has no shell',
    hint: 'Distroless and scratch images ship no /bin/sh, so there is nothing to run inside them. Another container in the pod may still be browsable.',
  },
  'no-exit-status': {
    tone: 'blocked',
    title: 'The container ran nothing',
    hint: 'The command exited without a status — almost always an image with no shell. Try another container in the pod.',
  },
  'container-not-running': {
    tone: 'blocked',
    title: 'The container is not running',
    hint: 'A filesystem can only be browsed while the container is up. Pick a container that is running, or wait for this one to start and retry.',
    retryable: true,
  },
  'container-command-timeout': {
    tone: 'blocked',
    title: 'The container did not answer in time',
    hint: 'kweblens.files.command-timeout elapsed. A very large directory, or a busy node, can do this — retry, or open a smaller directory.',
    retryable: true,
  },
  'container-command-failed': {
    tone: 'error',
    title: 'The command failed in the container',
    retryable: true,
  },
  'path-outside-roots': {
    tone: 'blocked',
    title: 'Outside the allowed roots',
    hint: 'This deployment confines the browser to kweblens.files.allowed-roots.',
  },
  'unresolvable-path': {
    tone: 'blocked',
    title: 'Cannot confirm where this path leads',
    hint: 'While allowed-roots is set, a path the container cannot resolve is refused rather than followed.',
  },
  'file-too-large': {
    tone: 'blocked',
    title: 'Too large to transfer',
    // Deliberately does NOT suggest downloading instead: download is capped by the same
    // max-read-bytes, so it would fail identically. Saying so avoids sending the reader
    // down a path that cannot work.
    hint: 'kweblens refuses rather than handing back a silently truncated copy. Downloading is capped by the same limit, so raise kweblens.files.max-read-bytes to open it here.',
    // An upload is refused by a different setting, and it is refused BEFORE anything is
    // sent, so the file in the container is untouched — worth saying, because "too large"
    // otherwise reads like it might have half-arrived.
    writeHint:
      'Nothing was sent, so the container is unchanged. Uploads are capped by kweblens.files.max-write-bytes; raise it to allow a larger file.',
  },
  'file-not-found': { tone: 'blocked', title: 'No such file or directory', retryable: true },
  'not-a-directory': { tone: 'blocked', title: 'Not a directory' },
  'is-a-directory': { tone: 'blocked', title: 'That is a directory' },
  'not-a-regular-file': {
    tone: 'blocked',
    title: 'Not a regular file',
    hint: 'Devices, sockets and FIFOs have no contents to read.',
  },
  'file-not-readable': {
    tone: 'blocked',
    title: 'Not readable',
    hint: 'The container’s own user cannot read it — kweblens runs commands as that user, not as root.',
  },
  'file-not-writable': {
    tone: 'blocked',
    title: 'Not writable',
    hint: 'ConfigMap and Secret mounts are always read-only. The file is unchanged.',
  },
  'file-not-deletable': { tone: 'blocked', title: 'Not deletable' },
  'write-unverified': {
    tone: 'error',
    title: 'The write could not be verified',
    hint: 'The file was left unchanged rather than half-written.',
  },
  'invalid-file-path': { tone: 'blocked', title: 'That path is not usable' },
  'invalid-file-content': { tone: 'error', title: 'That content cannot be written' },
  'unknown-cluster': { tone: 'error', title: 'Unknown cluster' },
  // Client-side codes. They never come off the wire — nothing was sent — but they share the
  // notice surface so a refused drop reads like every other refusal instead of a bare
  // "request failed" for a request that was never made.
  'dropped-a-folder': {
    tone: 'blocked',
    title: 'A folder cannot be uploaded',
    hint: 'kweblens uploads one file at a time and never creates directories in a container. Open the folder and drop a file from inside it.',
  },
  'dropped-many': {
    tone: 'blocked',
    title: 'One file at a time',
    hint: 'Each upload is a single write into the container. Nothing was sent — drop one file, or upload them one after another.',
  },
  'dropped-unreadable': {
    tone: 'blocked',
    title: 'That could not be read',
    hint: 'The browser would not hand over its bytes. A dropped folder does this on browsers that do not report one as a folder; so does a file that moved or was unmounted since it was picked.',
  },
};

const GENERIC: NoticeShape = { tone: 'error', title: 'The file browser request failed', retryable: true };

/**
 * Codes raised in the browser, before anything is sent. They must not teach
 * {@link filesFeature} anything: refusing a dropped folder says nothing about whether the
 * server has the feature on.
 */
const CLIENT_CODES = new Set(['dropped-a-folder', 'dropped-many', 'dropped-unreadable', 'invalid-file-path']);

/** Whether a failure is "you are not signed in", which the shell handles by prompting. */
export function isAuthFailure(e: unknown): boolean {
  return e instanceof PodFileError && (e.status === 401 || e.status === 403) && e.code === '';
}

/** Turn any thrown value into something worth showing a human. */
export function noticeFor(e: unknown, direction: FileDirection = 'read'): FileNotice {
  if (!(e instanceof PodFileError)) {
    return { ...GENERIC, detail: String(e), hint: null, retryable: true };
  }
  const shape = NOTICES[e.code] ?? GENERIC;
  const hint = (direction === 'write' && shape.writeHint) || shape.hint;
  return {
    tone: shape.tone,
    title: shape.title,
    detail: e.message,
    hint: hint ?? null,
    retryable: shape.retryable ?? false,
  };
}

/**
 * What this kweblens instance has told us about the feature, remembered for the page
 * session.
 *
 * <p>There is no endpoint that reports it: `/api/v1/about` predates the file browser and
 * cannot be extended from the UI side, and every files endpoint needs a pod. So
 * availability is learned from the first real answer — the first listing either works or
 * comes back `files-disabled` — and cached here. Once it is known to be off, the Files tab
 * stops being offered at all, so no drawer shows a tab that can only ever 403.
 *
 * <p>`writable` is a second, independent gate (`kweblens.files.writable`) with no
 * pre-flight either. Editing is therefore offered optimistically and withdrawn the moment
 * a write comes back `files-read-only` — never assumed.
 */
type FilesFeatureState = 'unknown' | 'enabled' | 'disabled';

const state = ref<FilesFeatureState>('unknown');
const writable = ref<boolean>(true);
const writeLimit = ref<number | null>(null);
const allowedRoots = ref<string[]>([]);

export const filesFeature = {
  /** `unknown` until the first answer; the Files tab is hidden only once `disabled`. */
  state,
  /** False once a write has been refused with `files-read-only`. */
  writable,
  /**
   * `kweblens.files.max-write-bytes`, when the server reports it. Null on a server that
   * does not, and then an oversized upload is refused by the server's own 413 instead of
   * here — the cap is the server's either way, this only avoids reading a huge file into
   * the browser to have it rejected.
   */
  writeLimit,
  /**
   * `kweblens.files.allowed-roots`, when the server reports it. Empty means unconfined —
   * and also means "an older server that does not report it", which is the same behaviour
   * either way: start at `/` and let the server refuse anything it will not serve. The
   * confinement is the server's; this only stops the browser opening on a path that a
   * confined deployment will always refuse.
   */
  allowedRoots,
  /** A request that reached the feature — so it is enabled, whatever it then said. */
  noteReached(): void {
    state.value = 'enabled';
  },
  /**
   * Seed from `/api/v1/about`, so the Files tab is right on FIRST paint rather than after
   * a request that exists only to fail.
   *
   * <p>A server that does not report `podFiles` predates the field. That is left as
   * `unknown` on purpose: the tab is shown and the first listing settles it, which is the
   * old behaviour. Guessing `disabled` would hide a working feature.
   */
  noteAbout(
    about: {
      podFiles?: { enabled: boolean; writable: boolean; maxWriteBytes?: number; allowedRoots?: string[] };
    } | null,
  ): void {
    if (!about?.podFiles) {
      return;
    }
    state.value = about.podFiles.enabled ? 'enabled' : 'disabled';
    writable.value = about.podFiles.writable;
    writeLimit.value = about.podFiles.maxWriteBytes ?? null;
    allowedRoots.value = about.podFiles.allowedRoots ?? [];
  },
  /** Learn from a failure: only `files-disabled` and `files-read-only` are verdicts. */
  noteFailure(e: unknown): void {
    if (!(e instanceof PodFileError) || CLIENT_CODES.has(e.code)) {
      return;
    }
    if (e.code === 'files-disabled') {
      state.value = 'disabled';
      return;
    }
    if (e.code === 'files-read-only') {
      state.value = 'enabled';
      writable.value = false;
      return;
    }
    if (e.code !== '' && e.status !== 401) {
      // Any other coded failure came from inside the feature, so the feature is on.
      state.value = 'enabled';
    }
  },
  /** Test hook: forget what was learned. */
  reset(): void {
    state.value = 'unknown';
    writable.value = true;
    writeLimit.value = null;
    allowedRoots.value = [];
  },
};

// --- the pod's containers ------------------------------------------------------------

/** One choice in the container picker. */
export interface ContainerChoice {
  name: string;
  /** An init container: listed, but its filesystem is usually gone once it has completed. */
  init: boolean;
}

/**
 * The containers of a pod, ordinary ones first, then init containers.
 *
 * <p>Init containers are offered deliberately even though most of them have exited: a pod
 * stuck in `Init:CrashLoopBackOff` is exactly when someone wants to look inside one, and
 * when the container really has gone the server says `container-not-running` — a specific,
 * true answer — rather than an empty tree.
 */
export function containerChoices(pod: KubeObject): ContainerChoice[] {
  const spec = objSpec(pod);
  const named = (key: string): string[] =>
    ((spec[key] as { name?: string }[]) ?? []).map((c) => c.name ?? '').filter(Boolean);
  return [
    ...named('containers').map((name) => ({ name, init: false })),
    ...named('initContainers').map((name) => ({ name, init: true })),
  ];
}

// --- paths ---------------------------------------------------------------------------

/** The containing directory of an absolute path (`/` is its own parent). */
export function parentPath(path: string): string {
  const slash = path.lastIndexOf('/');
  if (slash <= 0) {
    return '/';
  }
  return path.slice(0, slash);
}

/** Join a directory and an entry name without doubling the separator. */
export function joinPath(dir: string, name: string): string {
  return dir.endsWith('/') ? dir + name : dir + '/' + name;
}

/** The clickable trail for a path: `/` first, then one crumb per segment. */
export function breadcrumbs(path: string): { label: string; path: string }[] {
  const crumbs = [{ label: '/', path: '/' }];
  let at = '';
  for (const segment of path.split('/').filter(Boolean)) {
    at += '/' + segment;
    crumbs.push({ label: segment, path: at });
  }
  return crumbs;
}

/** Trailing separators, dropped without a regex: `/tmp/` and `/tmp` are the same root. */
function trimTrailingSlashes(value: string): string {
  let end = value.length;
  while (end > 0 && value[end - 1] === '/') {
    end -= 1;
  }
  return value.slice(0, end);
}

/**
 * Whether a path is one the server would serve, given the configured roots.
 *
 * <p>A mirror of the server's own check, and <strong>only</strong> an affordance: the
 * server re-checks every request, and re-checks it again against the path the container
 * actually resolves ({@code readlink -f}), which a browser cannot see. This exists so the
 * pane does not offer a journey that ends in a 403 — never as the thing that stops one.
 */
export function withinRoots(path: string, roots: string[]): boolean {
  if (roots.length === 0) {
    return true;
  }
  return roots.some((raw) => {
    const root = trimTrailingSlashes(raw);
    return root === '' || path === root || path.startsWith(root + '/');
  });
}

/**
 * Where the browser should open. Unconfined that is `/`; confined it is the first root,
 * because `/` is a path a confined server refuses — so opening there made the first thing
 * every reader saw an error about a directory they had not asked for.
 */
export function startPath(roots: string[]): string {
  const first = roots.length > 0 ? trimTrailingSlashes(roots[0]) : '';
  return first ? first : '/';
}

/**
 * Whether clicking an entry should open a directory. A symlink counts only when the
 * container resolved its target to a directory — a broken link reports no `linkType`, and
 * treating that as a directory would navigate into a path that does not exist.
 */
export function isDirectory(entry: PodFileEntry): boolean {
  return entry.type === 'dir' || (entry.type === 'symlink' && entry.linkType === 'dir');
}

/** Whether the entry has contents kweblens can try to read (a regular file, or a link to one). */
export function isReadable(entry: PodFileEntry): boolean {
  return entry.type === 'file' || (entry.type === 'symlink' && entry.linkType === 'file');
}

// --- upload --------------------------------------------------------------------------

/**
 * What the pane needs in order to PUT a picked file: where it goes, and its bytes in the
 * form the write endpoint takes.
 */
export interface UploadPlan {
  /** Absolute path inside the container. */
  path: string;
  /** The file's own name, for the confirm text and the "uploaded" line. */
  name: string;
  size: number;
  /** Base64 of the exact bytes — never `text`, since a picked file may be anything. */
  base64: string;
}

/** The smallest part of a browser `File` this module needs, so it can be tested without one. */
export interface UploadSource {
  name: string;
  size: number;
  arrayBuffer(): Promise<ArrayBuffer>;
}

/**
 * The name a picked file would take in `dir`, refusing anything that is not a plain file
 * name.
 *
 * <p>A browser reports a basename, so this is not the primary defence — the server
 * normalises and re-checks every path — but a name carrying a separator or `..` would
 * silently write somewhere other than the directory on screen, and a write that lands
 * somewhere the reader did not look at is exactly the mistake this feature must not make.
 */
export function uploadPath(dir: string, name: string): string {
  if (!name || name === '.' || name === '..' || name.includes('/') || name.includes('\0')) {
    throw new PodFileError(400, 'invalid-file-path', `"${name}" is not a usable file name.`);
  }
  return joinPath(dir, name);
}

/**
 * The parts of a `DataTransfer` a drop is read through, so the decision can be tested
 * without a browser.
 *
 * <p>Both halves matter. `items[i].webkitGetAsEntry()` is the only thing that says whether
 * a dropped item is a *directory*; `files` is the only thing that hands over bytes. A
 * `DataTransfer` synthesised in a test — or an older browser — may carry only `files`.
 */
interface DroppedEntry {
  isDirectory: boolean;
}

interface DroppedItem {
  kind: string;
  webkitGetAsEntry?: () => DroppedEntry | null;
}

export interface DropSource {
  files?: ArrayLike<File> | null;
  items?: ArrayLike<DroppedItem> | null;
}

/**
 * The single file a drop is offering, or `null` when it offered nothing droppable at all
 * (dragged text, an image from another tab). Throws {@link PodFileError} when it offered
 * something this pane will not take.
 *
 * <p><strong>A dropped directory is the case worth naming.</strong> A browser reports one
 * in `files` as an ordinary `File` — a plausible name, `type` empty, a small size — and
 * only refuses when its bytes are asked for, with a `DOMException` that says nothing
 * useful. Left alone it surfaces as a generic failure for what is really a simple mistake.
 * `webkitGetAsEntry()` knows the difference before anything is read, so the refusal can say
 * "that is a folder"; {@link readUpload} covers the browsers that do not report one.
 *
 * <p>Several files are refused rather than silently reduced to the first: one upload is one
 * write, and quietly dropping four of five would be discovered only by re-reading the
 * directory.
 */
export function fileFromDrop(source: DropSource | null | undefined): File | null {
  const files = Array.from(source?.files ?? []);
  const entries = Array.from(source?.items ?? []).filter((i) => i.kind === 'file');
  if (entries.some((i) => i.webkitGetAsEntry?.()?.isDirectory)) {
    throw new PodFileError(400, 'dropped-a-folder', 'A folder was dropped, and folders are not uploaded.');
  }
  const count = Math.max(files.length, entries.length);
  if (count > 1) {
    throw new PodFileError(400, 'dropped-many', `${count} files were dropped; kweblens uploads one at a time.`);
  }
  return files[0] ?? null;
}

/** The entry a new file would overwrite, when the directory already holds that name. */
export function uploadConflict(listing: PodDirectoryListing | null, name: string): PodFileEntry | null {
  return listing?.entries.find((e) => e.name === name) ?? null;
}

/** Base64 of raw bytes, chunked so a megabyte does not blow the argument limit of `apply`. */
export function base64Of(bytes: Uint8Array): string {
  const CHUNK = 0x8000;
  let binary = '';
  for (let i = 0; i < bytes.length; i += CHUNK) {
    binary += String.fromCharCode(...bytes.subarray(i, i + CHUNK));
  }
  return btoa(binary);
}

/**
 * Read a picked file into an {@link UploadPlan}, refusing an oversized one BEFORE it is
 * read.
 *
 * <p>The refusal is the same `file-too-large` the server answers with, deliberately: the
 * cap is the server's (`kweblens.files.max-write-bytes`, reported on `/api/v1/about`) and
 * checking it here only avoids pulling a gigabyte into the tab to be told no. The file is
 * never truncated to fit — a partial upload would overwrite whatever is in the container
 * with a fragment of what was chosen.
 *
 * <p>A read that fails is given its own code rather than escaping as a raw
 * `DOMException: NotFoundError`. That is the second half of the dropped-folder story: where
 * {@link fileFromDrop} cannot tell (no `webkitGetAsEntry`), the folder gets this far and the
 * browser refuses at `arrayBuffer()` with an exception that names no cause.
 */
export async function readUpload(dir: string, file: UploadSource, limit: number | null): Promise<UploadPlan> {
  const path = uploadPath(dir, file.name);
  if (limit !== null && file.size > limit) {
    throw new PodFileError(
      413,
      'file-too-large',
      `${file.name} is ${formatSize(file.size)}; this kweblens accepts at most ${formatSize(limit)} per write.`,
    );
  }
  let bytes: Uint8Array;
  try {
    bytes = new Uint8Array(await file.arrayBuffer());
  } catch {
    throw new PodFileError(400, 'dropped-unreadable', `${file.name} could not be read, so nothing was sent.`);
  }
  return { path, name: file.name, size: bytes.length, base64: base64Of(bytes) };
}

// --- display -------------------------------------------------------------------------

const RWX = ['---', '--x', '-w-', '-wx', 'r--', 'r-x', 'rw-', 'rwx'];

/** Octal permission bits as `rwxr-xr-x`; the raw value when it is not three octal digits. */
export function formatMode(mode: string | null): string {
  if (!mode) {
    return '—';
  }
  const digits = mode.length > 3 ? mode.slice(-3) : mode.padStart(3, '0');
  if (!/^[0-7]{3}$/.test(digits)) {
    return mode;
  }
  return [...digits].map((d) => RWX[Number(d)]).join('');
}

const UNITS = ['B', 'KiB', 'MiB', 'GiB', 'TiB'];

/** A byte count at three significant-ish digits — `null` renders as an em dash, not `0`. */
export function formatSize(bytes: number | null): string {
  if (bytes === null || bytes === undefined) {
    return '—';
  }
  let value = bytes;
  let unit = 0;
  while (value >= 1024 && unit < UNITS.length - 1) {
    value /= 1024;
    unit += 1;
  }
  if (unit === 0) {
    return `${value} ${UNITS[0]}`;
  }
  return `${value.toFixed(value < 10 ? 1 : 0)} ${UNITS[unit]}`;
}

/** Epoch seconds as local time; `—` when the container had no usable `stat`. */
export function formatModified(epochSeconds: number | null): string {
  if (!epochSeconds) {
    return '—';
  }
  return new Date(epochSeconds * 1000).toLocaleString();
}
