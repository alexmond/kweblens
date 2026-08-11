import { api } from './api';
import { auth } from './auth';

/**
 * Signing in and out. Both halves are here rather than in `App.vue` because both are
 * decisions, not rendering: one says whether a password was accepted, the other has to
 * reach the server before the UI may claim you are signed out.
 */

/**
 * Sign in: hold the credentials, then have the server check them.
 *
 * <p>The server is the only judge. `POST /api/v1/auth/session` is answered by the
 * credentials this request presents — a session cookie left over from an earlier sign-in no
 * longer answers for them (#320) — so a `true` here means the password was verified.
 */
export async function signIn(user: string, pass: string): Promise<boolean> {
  auth.set(user, pass);
  try {
    await api.verifySession();
    return true;
  } catch {
    auth.clear();
    return false;
  }
}

/**
 * Sign out: end the session on the SERVER, then drop the local credentials.
 *
 * <p>Dropping the local credentials alone was the whole of sign-out and left the
 * `JSESSIONID` valid — and that cookie still authorised every write and still opened the
 * exec WebSocket, which authenticates from it because a browser cannot attach Basic
 * credentials to a WebSocket handshake (#320).
 *
 * <p>The local clear is in a `finally`: a sign-out the network ate must still leave the tab
 * signed out. That is the weaker of the two guarantees, so the request goes first.
 */
export async function signOut(): Promise<void> {
  try {
    await api.endSession();
  } catch {
    // Already gone, or unreachable. Either way the local state below is what the operator
    // asked for, and a failed sign-out must not leave the UI claiming to be signed in.
  } finally {
    auth.clear();
  }
}
