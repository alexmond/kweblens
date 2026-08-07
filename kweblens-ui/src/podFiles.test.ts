import { readdirSync, readFileSync } from 'node:fs';
import { join, resolve } from 'node:path';

import { beforeEach, describe, expect, it } from 'vitest';

import {
  base64Of,
  breadcrumbs,
  containerChoices,
  filesFeature,
  formatMode,
  formatModified,
  formatSize,
  isAuthFailure,
  isDirectory,
  isReadable,
  fileFromDrop,
  joinPath,
  noticeFor,
  parentPath,
  PodFileError,
  readUpload,
  startPath,
  uploadConflict,
  uploadPath,
  withinRoots,
} from './podFiles';
import type { PodFileEntry } from './types';

const entry = (over: Partial<PodFileEntry>): PodFileEntry => ({
  name: 'x',
  type: 'file',
  size: null,
  mode: null,
  modified: null,
  owner: null,
  group: null,
  linkTarget: null,
  linkType: null,
  ...over,
});

describe('noticeFor', () => {
  it('treats the feature being switched off as a state, not an error', () => {
    const n = noticeFor(new PodFileError(403, 'files-disabled', 'the pod file browser is disabled.'));
    expect(n.tone).toBe('off');
    expect(n.detail).toContain('disabled');
    expect(n.hint).toContain('kweblens.files.enabled=true');
    expect(n.retryable).toBe(false);
  });

  it('keeps every container refusal distinct', () => {
    const codes = ['no-shell', 'no-exit-status', 'container-not-running', 'container-command-timeout'];
    const titles = codes.map((c) => noticeFor(new PodFileError(501, c, 'x')).title);
    expect(new Set(titles).size).toBe(codes.length);
    expect(noticeFor(new PodFileError(409, 'container-not-running', 'x')).retryable).toBe(true);
    expect(noticeFor(new PodFileError(501, 'no-shell', 'x')).retryable).toBe(false);
  });

  it('never suggests downloading a file that was refused for size', () => {
    // Download is capped by the same max-read-bytes, so "download it instead" would send
    // the reader at a request that fails identically.
    const n = noticeFor(new PodFileError(413, 'file-too-large', 'too big'));
    expect(n.hint).toMatch(/Downloading is capped by the same limit/);
    expect(n.hint).toContain('max-read-bytes');
  });

  it('names the write cap, not the read cap, when it was an upload that was refused', () => {
    const n = noticeFor(new PodFileError(413, 'file-too-large', 'too big'), 'write');
    expect(n.hint).toContain('max-write-bytes');
    expect(n.hint).not.toContain('max-read-bytes');
    // And says the container is untouched: "too large" otherwise reads as a half-write.
    expect(n.hint).toMatch(/unchanged/);
  });

  it('keeps the same title and the server’s own sentence in either direction', () => {
    const e = new PodFileError(413, 'file-too-large', 'payload is 9 bytes');
    expect(noticeFor(e, 'write').title).toBe(noticeFor(e).title);
    expect(noticeFor(e, 'write').detail).toBe('payload is 9 bytes');
  });

  it('falls back to a generic, retryable error for an unknown code or a plain throw', () => {
    expect(noticeFor(new PodFileError(502, 'brand-new-code', 'boom')).tone).toBe('error');
    const n = noticeFor(new Error('network down'));
    expect(n.tone).toBe('error');
    expect(n.detail).toContain('network down');
  });
});

describe('isAuthFailure', () => {
  it('is true only for an uncoded 401/403 — a coded 403 is a real answer', () => {
    expect(isAuthFailure(new PodFileError(401, '', '401 Unauthorized'))).toBe(true);
    expect(isAuthFailure(new PodFileError(403, 'files-disabled', 'off'))).toBe(false);
    expect(isAuthFailure(new Error('nope'))).toBe(false);
  });
});

describe('filesFeature', () => {
  beforeEach(() => filesFeature.reset());

  it('starts unknown so the tab is offered until proven otherwise', () => {
    expect(filesFeature.state.value).toBe('unknown');
    expect(filesFeature.writable.value).toBe(true);
  });

  it('remembers that the feature is off', () => {
    filesFeature.noteFailure(new PodFileError(403, 'files-disabled', 'off'));
    expect(filesFeature.state.value).toBe('disabled');
  });

  it('withdraws editing when a write is refused, without disabling the feature', () => {
    filesFeature.noteFailure(new PodFileError(403, 'files-read-only', 'read-only'));
    expect(filesFeature.writable.value).toBe(false);
    expect(filesFeature.state.value).toBe('enabled');
  });

  it('treats any other coded failure as proof the feature is on', () => {
    filesFeature.noteFailure(new PodFileError(501, 'no-shell', 'no shell'));
    expect(filesFeature.state.value).toBe('enabled');
  });

  it('learns nothing from a 401 or a network failure', () => {
    filesFeature.noteFailure(new PodFileError(401, '', 'Unauthorized'));
    filesFeature.noteFailure(new Error('offline'));
    expect(filesFeature.state.value).toBe('unknown');
  });

  it('takes both gates and the write cap from /about', () => {
    filesFeature.noteAbout({ podFiles: { enabled: true, writable: false, maxWriteBytes: 1024 } });
    expect(filesFeature.state.value).toBe('enabled');
    expect(filesFeature.writable.value).toBe(false);
    expect(filesFeature.writeLimit.value).toBe(1024);
  });

  it('leaves the write cap unknown on a server that does not report one', () => {
    // Then the server's own 413 is the refusal, which is where the cap lives anyway.
    filesFeature.noteAbout({ podFiles: { enabled: true, writable: true } });
    expect(filesFeature.writeLimit.value).toBeNull();
  });
});

describe('upload', () => {
  beforeEach(() => filesFeature.reset());

  const source = (name: string, bytes: number[]) => ({
    name,
    size: bytes.length,
    arrayBuffer: () => Promise.resolve(new Uint8Array(bytes).buffer),
  });

  it('puts the file in the directory on screen', () => {
    expect(uploadPath('/srv/app', 'notes.txt')).toBe('/srv/app/notes.txt');
    expect(uploadPath('/', 'notes.txt')).toBe('/notes.txt');
  });

  it('refuses a name that would write somewhere other than the directory shown', () => {
    // A browser reports a basename, so this is a backstop — but a write that lands
    // somewhere the reader did not look is the one mistake this must not make.
    for (const bad of ['../escape', 'a/b', '..', '.', '']) {
      expect(() => uploadPath('/srv/app', bad)).toThrow(PodFileError);
    }
    expect(() => uploadPath('/srv/app', '../escape')).toThrow(/not a usable file name/);
  });

  it('refuses an oversized file before reading it, and says nothing was sent', async () => {
    let read = false;
    const big = {
      name: 'big.bin',
      size: 5000,
      arrayBuffer: () => {
        read = true;
        return Promise.resolve(new ArrayBuffer(5000));
      },
    };
    await expect(readUpload('/srv/app', big, 1024)).rejects.toMatchObject({ status: 413, code: 'file-too-large' });
    expect(read).toBe(false);
  });

  it('leaves the refusal to the server when the cap is unknown', async () => {
    const plan = await readUpload('/srv/app', source('x.txt', [104, 105]), null);
    expect(plan.path).toBe('/srv/app/x.txt');
    expect(plan.size).toBe(2);
  });

  it('sends the exact bytes as base64, so a binary survives the trip', async () => {
    // 0x00 and 0xff would both be mangled by any text round trip.
    const plan = await readUpload('/srv/app', source('b.bin', [0, 255, 65]), 1024);
    expect(plan.base64).toBe(base64Of(new Uint8Array([0, 255, 65])));
    expect([...atob(plan.base64)].map((c) => c.charCodeAt(0))).toEqual([0, 255, 65]);
  });

  it('encodes a payload larger than one chunk', () => {
    const bytes = new Uint8Array(70_000).fill(7);
    expect(atob(base64Of(bytes))).toHaveLength(70_000);
  });

  it('spots the entry an upload would replace', () => {
    const listing = {
      path: '/srv/app',
      resolvedPath: '/srv/app',
      container: 'app',
      entries: [entry({ name: 'keep.txt' })],
      truncated: false,
    };
    expect(uploadConflict(listing, 'keep.txt')?.name).toBe('keep.txt');
    expect(uploadConflict(listing, 'new.txt')).toBeNull();
    expect(uploadConflict(null, 'keep.txt')).toBeNull();
  });
});

describe('drop', () => {
  beforeEach(() => filesFeature.reset());

  const dropped = (name: string) => new File([new Uint8Array([1, 2, 3])], name);
  const item = (isDirectory: boolean) => ({ kind: 'file', webkitGetAsEntry: () => ({ isDirectory }) });

  it('takes the one file that was dropped', () => {
    const file = fileFromDrop({ files: [dropped('notes.txt')], items: [item(false)] });
    expect(file?.name).toBe('notes.txt');
  });

  it('says a folder is a folder, instead of failing on its bytes later', () => {
    // A browser reports a dropped directory in `files` as an ordinary File and only
    // refuses when its bytes are asked for, with a DOMException that names no cause.
    // webkitGetAsEntry knows before anything is read.
    expect(() => fileFromDrop({ files: [dropped('config')], items: [item(true)] })).toThrow(PodFileError);
    try {
      fileFromDrop({ files: [dropped('config')], items: [item(true)] });
    } catch (e) {
      expect(noticeFor(e, 'write').title).toBe('A folder cannot be uploaded');
      expect(noticeFor(e, 'write').hint).toContain('one file at a time');
    }
  });

  it('refuses several files rather than silently uploading the first', () => {
    const many = { files: [dropped('a.txt'), dropped('b.txt')], items: [item(false), item(false)] };
    expect(() => fileFromDrop(many)).toThrow(/2 files were dropped/);
  });

  it('ignores a drop that carries no file at all', () => {
    expect(fileFromDrop({ files: [], items: [{ kind: 'string' }] })).toBeNull();
    expect(fileFromDrop(null)).toBeNull();
  });

  it('names an unreadable file instead of leaking a DOMException', async () => {
    // The fallback for a browser that does not report a directory: it gets as far as
    // arrayBuffer(), which rejects.
    const unreadable = {
      name: 'config',
      size: 4096,
      arrayBuffer: () => Promise.reject(new Error('NotFoundError')),
    };
    await expect(readUpload('/srv/app', unreadable, null)).rejects.toMatchObject({ code: 'dropped-unreadable' });
    expect(noticeFor(new PodFileError(400, 'dropped-unreadable', 'x')).tone).toBe('blocked');
  });

  it('learns nothing about the server from a refusal the browser made', () => {
    // Nothing was sent, so the feature's state is still whatever it was.
    filesFeature.noteFailure(new PodFileError(400, 'dropped-a-folder', 'x'));
    expect(filesFeature.state.value).toBe('unknown');
  });
});

describe('allowed roots', () => {
  beforeEach(() => filesFeature.reset());

  it('opens at the first root when the deployment is confined', () => {
    expect(startPath([])).toBe('/');
    expect(startPath(['/srv/data'])).toBe('/srv/data');
    expect(startPath(['/var/log/'])).toBe('/var/log');
  });

  it('mirrors the server on what is inside a root', () => {
    expect(withinRoots('/etc', [])).toBe(true);
    expect(withinRoots('/srv/data', ['/srv/data'])).toBe(true);
    expect(withinRoots('/srv/data/sub/x', ['/srv/data'])).toBe(true);
    expect(withinRoots('/', ['/srv/data'])).toBe(false);
    expect(withinRoots('/etc', ['/srv/data'])).toBe(false);
    // A prefix match on the string alone would let /srv/database through.
    expect(withinRoots('/srv/database', ['/srv/data'])).toBe(false);
    expect(withinRoots('/anything', ['/'])).toBe(true);
  });

  it('takes the roots off /about, and forgets them on reset', () => {
    filesFeature.noteAbout({ podFiles: { enabled: true, writable: true, allowedRoots: ['/srv/data'] } });
    expect(filesFeature.allowedRoots.value).toEqual(['/srv/data']);
    // A server that predates the field is unconfined as far as the UI is concerned; the
    // server still refuses anything it will not serve.
    filesFeature.noteAbout({ podFiles: { enabled: true, writable: true } });
    expect(filesFeature.allowedRoots.value).toEqual([]);
  });
});

describe('paths', () => {
  it('walks up, stopping at the root', () => {
    expect(parentPath('/etc/nginx/nginx.conf')).toBe('/etc/nginx');
    expect(parentPath('/etc')).toBe('/');
    expect(parentPath('/')).toBe('/');
  });

  it('joins without doubling the separator', () => {
    expect(joinPath('/', 'etc')).toBe('/etc');
    expect(joinPath('/etc', 'passwd')).toBe('/etc/passwd');
  });

  it('builds a trail from the root', () => {
    expect(breadcrumbs('/var/log')).toEqual([
      { label: '/', path: '/' },
      { label: 'var', path: '/var' },
      { label: 'log', path: '/var/log' },
    ]);
    expect(breadcrumbs('/')).toEqual([{ label: '/', path: '/' }]);
  });
});

describe('entries', () => {
  it('follows a symlink only when the container resolved it', () => {
    expect(isDirectory(entry({ type: 'dir' }))).toBe(true);
    expect(isDirectory(entry({ type: 'symlink', linkType: 'dir' }))).toBe(true);
    // A broken link reports no linkType; navigating into it would open a path that does
    // not exist, so it is neither a directory nor readable.
    expect(isDirectory(entry({ type: 'symlink', linkType: null }))).toBe(false);
    expect(isReadable(entry({ type: 'symlink', linkType: null }))).toBe(false);
    expect(isReadable(entry({ type: 'symlink', linkType: 'file' }))).toBe(true);
    expect(isReadable(entry({ type: 'other' }))).toBe(false);
  });
});

describe('display', () => {
  it('renders permission bits, and passes anything unexpected through', () => {
    expect(formatMode('644')).toBe('rw-r--r--');
    expect(formatMode('0755')).toBe('rwxr-xr-x');
    expect(formatMode(null)).toBe('—');
    expect(formatMode('u+rw')).toBe('u+rw');
  });

  it('distinguishes an unknown size from zero', () => {
    expect(formatSize(null)).toBe('—');
    expect(formatSize(0)).toBe('0 B');
    expect(formatSize(2048)).toBe('2.0 KiB');
    expect(formatSize(5 * 1024 * 1024)).toBe('5.0 MiB');
  });

  it('renders a missing timestamp as unknown rather than the epoch', () => {
    expect(formatModified(null)).toBe('—');
    expect(formatModified(1_700_000_000)).toContain('2023');
  });
});

describe('containerChoices', () => {
  it('lists ordinary containers first, then init containers, flagged', () => {
    const pod = { spec: { containers: [{ name: 'app' }], initContainers: [{ name: 'setup' }] } };
    expect(containerChoices(pod)).toEqual([
      { name: 'app', init: false },
      { name: 'setup', init: true },
    ]);
    expect(containerChoices({})).toEqual([]);
  });
});

describe('the notice table really is exhaustive', () => {
  // The table's own doc comment claims one entry per code the server can send. It claimed
  // that while `unreadable-listing` and `unreadable-stat` had no entry, and nothing noticed,
  // because a missing entry does not fail — it degrades to a generic title. So the claim is
  // checked against the server's source rather than against a second hand-written list,
  // which would be the copy that goes stale next.
  // Resolved from the vitest root (kweblens-ui), not from import.meta.url — Vite does not
  // hand test modules a file: URL. If this path is ever wrong the positive control below
  // fails rather than the check quietly passing on an empty directory.
  const dir = resolve(process.cwd(), '../kweblens-web/src/main/java/org/alexmond/kweblens/web/files');

  const serverCodes = (): string[] => {
    const codes = new Set<string>();
    for (const f of readdirSync(dir).filter((n) => n.endsWith('.java'))) {
      const src = readFileSync(join(dir, f), 'utf8');
      for (const m of src.matchAll(/PodFileException\([^,)]+,\s*"([a-z-]+)"/g)) {
        codes.add(m[1]);
      }
    }
    return [...codes].sort();
  };

  it('finds the server’s codes at all — a grep that matched nothing would pass vacuously', () => {
    // The positive control. Without it the assertion below is "no codes found, no failures".
    const codes = serverCodes();
    expect(codes.length).toBeGreaterThan(15);
    expect(codes).toContain('no-shell');
  });

  it('explains every code web/files can raise', () => {
    const generic = noticeFor(new PodFileError(500, 'definitely-not-a-real-code', 'x')).title;
    const unexplained = serverCodes().filter((c) => noticeFor(new PodFileError(502, c, 'x')).title === generic);
    expect(unexplained).toEqual([]);
  });
});
