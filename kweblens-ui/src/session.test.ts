import { beforeEach, describe, expect, it, vi } from 'vitest';

// Only the two session calls are stubbed; `auth` stays real, because what these tests are
// really asserting is the ORDER of a network call against local credential state (#320).
const { verifySession, endSession } = vi.hoisted(() => ({
  verifySession: vi.fn<() => Promise<{ user: string }>>(() => Promise.resolve({ user: 'admin' })),
  endSession: vi.fn<() => Promise<void>>(() => Promise.resolve()),
}));
vi.mock('./api', async (orig) => ({
  ...((await orig()) as object),
  api: { verifySession, endSession },
}));

const { auth } = await import('./auth');
const { signIn, signOut } = await import('./session');

describe('signIn', () => {
  beforeEach(() => {
    auth.clear();
    verifySession.mockReset();
  });

  it('holds the credentials only while the server accepts them', async () => {
    verifySession.mockResolvedValue({ user: 'admin' });
    await expect(signIn('admin', 'right')).resolves.toBe(true);
    expect(auth.header()).toEqual({ Authorization: 'Basic ' + btoa('admin:right') });
  });

  it('reports a refusal and keeps no credentials', async () => {
    // The server is the only judge of a password. Before #320 it could not be one: the
    // request rode the session cookie left by an earlier sign-in and came back 200, so a
    // deliberately wrong password signed you in.
    verifySession.mockRejectedValue(new Error('401'));
    await expect(signIn('admin', 'wrong')).resolves.toBe(false);
    expect(auth.isSet()).toBe(false);
  });

  it('presents the typed credentials on the verifying request itself', async () => {
    // The header has to be set BEFORE the call, or the request carries nothing to check and
    // only a cookie can answer it.
    verifySession.mockImplementation(() => {
      expect(auth.header()).toEqual({ Authorization: 'Basic ' + btoa('admin:typed') });
      return Promise.resolve({ user: 'admin' });
    });
    await signIn('admin', 'typed');
    expect(verifySession).toHaveBeenCalledTimes(1);
  });
});

describe('signOut', () => {
  beforeEach(() => {
    auth.set('admin', 'right');
    endSession.mockReset();
    endSession.mockResolvedValue(undefined);
  });

  it('ends the server session, not just the local credentials', async () => {
    // The regression this file exists for: sign-out used to be `auth.clear()` and nothing
    // else, leaving a JSESSIONID that still authorised writes and still opened pod exec.
    await signOut();
    expect(endSession).toHaveBeenCalledTimes(1);
    expect(auth.isSet()).toBe(false);
  });

  it('asks the server before dropping the credentials the request needs', async () => {
    endSession.mockImplementation(() => {
      expect(auth.isSet()).toBe(true);
      return Promise.resolve();
    });
    await signOut();
    expect(auth.isSet()).toBe(false);
  });

  it('still signs the tab out when the request fails', async () => {
    endSession.mockRejectedValue(new Error('offline'));
    await expect(signOut()).resolves.toBeUndefined();
    expect(auth.isSet()).toBe(false);
  });
});
