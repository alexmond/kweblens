import { ref } from 'vue';

import { objSpec } from './kube';
import type { KubeObject, PodFileEntry } from './types';

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
  retryable?: boolean;
}

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
};

const GENERIC: NoticeShape = { tone: 'error', title: 'The file browser request failed', retryable: true };

/** Whether a failure is "you are not signed in", which the shell handles by prompting. */
export function isAuthFailure(e: unknown): boolean {
  return e instanceof PodFileError && (e.status === 401 || e.status === 403) && e.code === '';
}

/** Turn any thrown value into something worth showing a human. */
export function noticeFor(e: unknown): FileNotice {
  if (!(e instanceof PodFileError)) {
    return { ...GENERIC, detail: String(e), hint: null, retryable: true };
  }
  const shape = NOTICES[e.code] ?? GENERIC;
  return {
    tone: shape.tone,
    title: shape.title,
    detail: e.message,
    hint: shape.hint ?? null,
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

export const filesFeature = {
  /** `unknown` until the first answer; the Files tab is hidden only once `disabled`. */
  state,
  /** False once a write has been refused with `files-read-only`. */
  writable,
  /** A request that reached the feature — so it is enabled, whatever it then said. */
  noteReached(): void {
    state.value = 'enabled';
  },
  /** Learn from a failure: only `files-disabled` and `files-read-only` are verdicts. */
  noteFailure(e: unknown): void {
    if (!(e instanceof PodFileError)) {
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
