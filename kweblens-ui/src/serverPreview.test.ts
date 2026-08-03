import { describe, expect, it } from 'vitest';

import { ApiError } from './api';
import { isRefusal, requestServerPreview, serverPreviewCaption } from './serverPreview';

describe('requestServerPreview', () => {
  it('returns what the server would store', async () => {
    const state = await requestServerPreview(() => Promise.resolve('kind: ConfigMap\n'), 'x');
    expect(state).toEqual({ status: 'ready', yaml: 'kind: ConfigMap\n' });
  });

  it('treats a webhook rejection as a RESULT, not an error', async () => {
    // The distinction the whole feature turns on. A webhook saying no is this working; if it
    // rendered as a failed request the operator would think the tool broke, and the message
    // — which is the actual answer — would read as a stack trace.
    const denied = new ApiError(422, 'admission webhook "policy" denied the request: no owner label');
    const state = await requestServerPreview(() => Promise.reject(denied), 'x');
    expect(state.status).toBe('refused');
    expect(state).toMatchObject({ httpStatus: 422 });
    if (state.status === 'refused') {
      expect(state.message).toContain('denied the request');
    }
  });

  it('treats "we never got a verdict" as failed, not refused', async () => {
    // 401 means we did not authenticate and 502 that the cluster was unreachable — in
    // neither case has the cluster formed an opinion, so claiming it refused would be a
    // fabricated verdict.
    for (const status of [401, 502, 500]) {
      const state = await requestServerPreview(() => Promise.reject(new ApiError(status, 'nope')), 'x');
      expect(state.status).toBe('failed');
    }
  });

  it('survives a non-ApiError rejection', async () => {
    const state = await requestServerPreview(() => Promise.reject(new TypeError('offline')), 'x');
    expect(state).toMatchObject({ status: 'failed' });
  });
});

describe('isRefusal', () => {
  it('counts the statuses where the cluster gave a verdict', () => {
    expect([400, 403, 409, 422].every(isRefusal)).toBe(true);
    expect([401, 404, 500, 502, 0].some(isRefusal)).toBe(false);
  });
});

describe('serverPreviewCaption', () => {
  it('says an empty would-be diff is a real answer', () => {
    // Otherwise "the cluster would store exactly this" is indistinguishable from "nothing
    // loaded", and the most reassuring outcome looks like a broken panel.
    expect(serverPreviewCaption({ status: 'ready', yaml: 'a' }, false)).toContain('changes nothing');
    expect(serverPreviewCaption({ status: 'ready', yaml: 'a' }, true)).toContain('server');
  });

  it('says plainly when the check did not happen', () => {
    expect(serverPreviewCaption({ status: 'failed', message: 'x' }, false)).toContain('unchecked');
  });

  it('warns that applying would fail the same way', () => {
    expect(serverPreviewCaption({ status: 'refused', message: 'x', httpStatus: 422 }, false)).toContain('would fail');
  });
});
