import { beforeEach, describe, expect, it } from 'vitest';

import {
  breadcrumbs,
  containerChoices,
  filesFeature,
  formatMode,
  formatModified,
  formatSize,
  isAuthFailure,
  isDirectory,
  isReadable,
  joinPath,
  noticeFor,
  parentPath,
  PodFileError,
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
