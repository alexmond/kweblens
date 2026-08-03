// ---- "What the cluster would store" — the Review tab's second diff (T1) ----
//
// Review Changes could only ever answer "what did I type": it diffs the edited text against
// the text that was loaded. Three things live in the gap between that and what actually
// happens, and none of them are visible until the write has already landed:
//
//   defaulting          the fields the API server fills in that nobody typed
//   other managers      fields owned by another controller, which `forceConflicts()` takes
//   admission           a validating webhook that rejects this, a quota that blocks it, or
//                       a mutating webhook that rewrites it into something else
//
// `dryRun=All` answers all three, so the tab can show a second diff — **live → would-be** —
// beside the first. The two are different questions and both are worth asking: the first is
// "is my edit what I meant", the second is "will the cluster agree".

import { ApiError } from './api';

/**
 * The four states this can be in. Explicit rather than `loading` + `error` booleans, because
 * a **refusal is not an error** — a webhook saying no is the feature working, and rendering
 * it as a failed request would tell the operator the tool broke when it did its job.
 */
export type ServerPreviewState =
  | { status: 'idle' }
  | { status: 'loading' }
  /** The server answered: this is the object it would have stored. */
  | { status: 'ready'; yaml: string }
  /** The cluster refused, and said why. The message is the payload. */
  | { status: 'refused'; message: string; httpStatus: number }
  /** We could not ask — offline, signed out, the cluster unreachable. */
  | { status: 'failed'; message: string };

/**
 * Which HTTP statuses mean "the cluster considered this and said no", as opposed to "the
 * request never got a verdict".
 *
 * 422 is a validating webhook or a schema violation; 409 a conflict; 403 RBAC; 400 a
 * malformed object. All four are answers. A 401 is not — it means we never authenticated,
 * so the cluster has no opinion yet — and neither is a 502, which the API maps a failed
 * connection to.
 */
const REFUSAL_STATUSES = new Set([400, 403, 409, 422]);

export function isRefusal(httpStatus: number): boolean {
  return REFUSAL_STATUSES.has(httpStatus);
}

/**
 * Ask the cluster, and classify the outcome.
 *
 * `fetcher` is injected so this is testable with no network and no DOM — the component
 * passes `api.applyDryRun`.
 */
export async function requestServerPreview(
  fetcher: (manifest: string) => Promise<string>,
  manifest: string,
): Promise<ServerPreviewState> {
  try {
    return { status: 'ready', yaml: await fetcher(manifest) };
  } catch (e) {
    if (e instanceof ApiError) {
      return isRefusal(e.status)
        ? { status: 'refused', message: e.message, httpStatus: e.status }
        : { status: 'failed', message: e.message };
    }
    return { status: 'failed', message: String(e) };
  }
}

/**
 * What to tell the reader above the second diff.
 *
 * Worth stating rather than leaving the diff to speak: an EMPTY would-be diff is a real and
 * reassuring result ("the cluster would store exactly this"), and it is indistinguishable
 * from "nothing loaded yet" if nothing says so.
 */
export function serverPreviewCaption(state: ServerPreviewState, changed: boolean): string {
  switch (state.status) {
    case 'idle':
      return 'Ask the cluster what this manifest would become.';
    case 'loading':
      return 'Asking the cluster…';
    case 'ready':
      return changed
        ? 'The cluster would store this. Differences below are the server’s doing — defaulting, another controller’s fields, or a mutating webhook.'
        : 'The cluster would store exactly what is live now: this manifest changes nothing.';
    case 'refused':
      return 'The cluster refused this manifest. Applying it would fail the same way.';
    case 'failed':
      return 'Could not ask the cluster, so this is unchecked — the diff on the left is still only your edit versus what was loaded.';
  }
}
