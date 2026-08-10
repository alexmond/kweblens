import { describe, expect, it } from 'vitest';

// The real ApiError, as in apiFailure.test.ts: the consequence line branches through
// `classifyFailure`, which tests `instanceof`, so a stub would pass for the wrong reason.
import { ApiError } from './api';
import {
  actionConsequence,
  actionFailed,
  actionReport,
  bulkDeleteIncomplete,
  mayRetry,
  previewFailed,
  readFailed,
  signInRejected,
} from './paneFailure';

describe('mayRetry', () => {
  it('offers a retry for a read — repeating a fetch costs one more GET', () => {
    expect(mayRetry(readFailed(new ApiError(500, 'boom')))).toBe(true);
  });

  it('never offers one for an action, which is the whole point of the split', () => {
    // A Retry button here would offer to repeat a write the operator has not re-authorised.
    expect(mayRetry(actionFailed('Delete', new ApiError(500, 'boom')))).toBe(false);
    expect(mayRetry(signInRejected())).toBe(false);
    expect(mayRetry(actionReport('Start forward', 'Enter a valid pod port.'))).toBe(false);
  });

  it('has nothing to offer when nothing failed', () => {
    expect(mayRetry(null)).toBe(false);
  });
});

describe('actionConsequence', () => {
  it('says nothing was changed when the cluster gave a verdict', () => {
    for (const status of [400, 403, 409, 422]) {
      expect(actionConsequence(new ApiError(status, 'no', 'cluster-refused'))).toContain('considered and refused');
    }
  });

  it('says nothing was changed when kweblens itself refused, since the cluster never saw it', () => {
    expect(actionConsequence(new ApiError(401, '401 Unauthorized'))).toContain('before it reached the cluster');
  });

  it('refuses to claim a timeout changed nothing — a request with no answer can still land', () => {
    // CLAUDE.md's shipped instance: a script piped into a container hangs for the whole
    // command-timeout and the write lands anyway, i.e. failure is reported for a write that
    // happened. Saying "nothing was changed" here talks the operator out of checking.
    const unknown = actionConsequence(new Error('Request timed out after 20s'));
    expect(unknown).toContain('unknown');
    expect(unknown).not.toContain('Nothing was changed');
  });

  it('treats an unreachable cluster the same way — 502 is not a verdict', () => {
    expect(actionConsequence(new ApiError(502, 'Connection refused', 'cluster-refused'))).toContain('unknown');
  });

  it('treats a 500 the same way — kweblens broke somewhere, and where is not known', () => {
    expect(actionConsequence(new ApiError(500, 'boom'))).toContain('unknown');
  });
});

describe('actionFailed', () => {
  it('names the attempt as well as the error, because the modal that made it may be shut', () => {
    const f = actionFailed('Upgrade release', new ApiError(422, 'values.yaml is not valid YAML', 'cluster-refused'));
    expect(f.kind).toBe('action');
    expect(f.title).toBe('Upgrade release');
    expect(f.message).toBe('The cluster refused this: values.yaml is not valid YAML');
    expect(f.consequence).toContain('considered and refused');
  });
});

describe('actionReport', () => {
  it('adds no consequence line by default — its callers already state the outcome exactly', () => {
    // A bulk delete says "Deleted 7 of 10 …; 3 failed"; a general hedge underneath would be a
    // vaguer restatement of a sentence that is already precise.
    const f = actionReport('Delete', 'Deleted 7 of 10 Pods; 3 failed. ns/a: admission webhook denied the request');
    expect(f.consequence).toBeNull();
  });

  it('still carries one when the caller has something to add', () => {
    expect(actionReport('Delete', 'boom', 'Nothing was changed.').consequence).toBe('Nothing was changed.');
  });
});

describe('signInRejected', () => {
  it('is an action result, not a failed fetch', () => {
    const f = signInRejected();
    expect(f.kind).toBe('action');
    expect(f.title).toBe('Sign in failed');
  });

  it('says the login is shared, so nobody hunts for a personal account (ADR-001)', () => {
    // Nothing was attempted against the cluster, so the third line has no state to hedge
    // about — and this is the one place the shared-identity design is worth saying out loud,
    // because it is the moment a reader assumes the fault is with THEIR password.
    expect(signInRejected().consequence).toContain('single shared admin login');
  });
});

describe('previewFailed', () => {
  it('does NOT hedge about a dry run — a dry run applies nothing whatever goes wrong', () => {
    // The generic hedge would be a false alarm here: this is the one action whose
    // not-applied-ness is guaranteed by what it is, not by the status it came back with.
    const f = previewFailed(new Error('Request timed out after 20s'));
    expect(f.consequence).toContain('never applies anything');
    expect(f.consequence).not.toContain('unknown');
  });

  it('still refuses a retry — what needs changing is the values, not the request', () => {
    expect(mayRetry(previewFailed(new Error('boom')))).toBe(false);
  });
});

describe('bulkDeleteIncomplete', () => {
  it('does not call a partial delete a failure, because the message under it says otherwise', () => {
    const f = bulkDeleteIncomplete('Deleted 7 of 10 Pods; 3 failed.');
    expect(f.title).toBe('Delete did not finish');
    expect(f.consequence).toBeNull();
    expect(mayRetry(f)).toBe(false);
  });
});

describe('readFailed', () => {
  it('renders the server’s sentence through failureNotice rather than restating it', () => {
    expect(readFailed(new ApiError(502, 'Connection refused', 'cluster-refused'))).toEqual({
      kind: 'read',
      message: 'Could not reach the cluster: Connection refused',
    });
  });
});
