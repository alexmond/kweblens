/**
 * What a pane may OFFER after a request failed — and in particular whether "Retry" is a safe
 * thing to put in front of the operator.
 *
 * <p>This is the error half of roadmap R3, and it is not the mechanical sweep it looks like.
 * `ErrorNotice`'s Retry re-runs a *fetch*: pressing it costs one more GET and the worst case
 * is the same message again. Several of the panes that render a raw error div are not showing
 * a failed fetch at all — they are showing the result of an ACTION. "Invalid credentials.",
 * a Helm upgrade the cluster rejected, a port-forward that would not bind, a bulk delete that
 * got seven of ten. A Retry button on those offers to repeat a **write** that the operator has
 * not re-authorised, which is the opposite of the standing rule that remediation is
 * suggest → approve → apply and never autonomous (CLAUDE.md, `RemediationService`).
 *
 * <p>So the two are different claims and get different renderings, exactly as
 * `emptyState.ts` / `LoadingNotice` split "nothing here" from "still loading":
 *
 * <ul>
 * <li><b>read</b> — a fetch failed. `ErrorNotice`, with Retry.
 * <li><b>action</b> — something was attempted and did not complete. `ActionNotice`, with NO
 * Retry: the surface's own control (Sign in, Start forward, Apply) is the explicit re-do, and
 * it is one the operator presses knowingly.
 * </ul>
 *
 * <p><b>Two panes hold one slot for both.</b> That is the part the roadmap's "classify each
 * site" framing missed. `App.vue`'s single `error` ref is written by the clusters fetch, the
 * nav fetch, the object-list fetch AND by every row action, plus bulk delete; `PortForwards`
 * writes its poll's failure and its Stop button's failure into the same `error`. A per-site
 * verdict cannot be right for those — the classification belongs to the *writer*, not the
 * pane — which is why the state below is a discriminated union those two panes carry and
 * `FailureNotice` dispatches on.
 *
 * <p><b>A failed write is not a write that did not happen.</b> The consequence line comes from
 * `classifyFailure`, and it is deliberately hedged where the truth is unknown: a request that
 * timed out can still have landed. CLAUDE.md records the shipped instance of this — a script
 * piped into a container reads to EOF, hangs for the whole command timeout, and the write
 * lands anyway, so the UI "reports failure for a write that happened". Only a verdict
 * (400/403/409/422 — see `isRefusal`) and kweblens's own 401 let us say nothing changed.
 */

import { classifyFailure, failureNotice } from './apiFailure';

/** A fetch that failed. Repeating it costs one more GET, so the notice carries a Retry. */
export interface ReadFailure {
  kind: 'read';
  message: string;
}

/**
 * An attempt that did not complete. Repeating it would repeat whatever it was going to do, so
 * the notice carries no Retry.
 */
export interface ActionFailure {
  kind: 'action';
  /**
   * What was attempted and how it ended, in the surface's own words — "Sign in failed",
   * "Upgrade release failed", "Delete did not finish". Written out by the caller rather than
   * composed from a verb, because a partial outcome is not "failed" and a builder that
   * appended the word would make the heading contradict the message under it.
   */
  title: string;
  /** The server's sentence, or the surface's own when no request was ever made. */
  message: string;
  /**
   * What is known about the cluster afterwards, or null when nothing general can be said —
   * either because the action changes nothing cluster-side, or because `message` already
   * spells the outcome out (a partial bulk delete names its own counts).
   */
  consequence: string | null;
}

export type PaneFailure = ReadFailure | ActionFailure;

/**
 * The request was answered with a refusal, so it was considered and not applied.
 *
 * <p>`isRefusal`'s statuses are verdicts: the API server (or Helm, or the cluster-config
 * validator) read the request and said no. Nothing was written.
 */
const REFUSED = 'Nothing was changed — the request was considered and refused.';

/** kweblens's own 401. The request never reached the cluster, so there is nothing to undo. */
const REJECTED_LOCALLY = 'Nothing was changed — kweblens rejected this before it reached the cluster.';

/**
 * Everything else: a 500, a 502, a timeout, a dropped connection.
 *
 * <p>The hedge is the point. A request with no answer is not a request that did not happen,
 * and telling the operator it was is how a UI talks someone out of checking.
 */
const UNKNOWN =
  'Whether the cluster applied this is unknown — a request that fails without an answer can still have landed. Check before trying again.';

/** A fetch failure, ready to render. */
export function readFailed(e: unknown): ReadFailure {
  return { kind: 'read', message: failureNotice(e) };
}

/**
 * A fetch failure whose message was rendered elsewhere.
 *
 * <p>For the shell's composables, which already call `failureNotice` (and, for the Helm scope,
 * compose a longer sentence around it) before handing the string up.
 */
export function readMessage(message: string): ReadFailure {
  return { kind: 'read', message };
}

/** What is known about the cluster after `e` — see the constants above for why each. */
export function actionConsequence(e: unknown): string {
  const kind = classifyFailure(e).kind;
  if (kind === 'refused') {
    return REFUSED;
  }
  return kind === 'session' ? REJECTED_LOCALLY : UNKNOWN;
}

/**
 * A failed action, from the thrown value.
 *
 * <p>`title` names the attempt rather than restating the error, because the message alone
 * ("Forbidden: deployments.apps is forbidden") does not say which of the operator's clicks it
 * belongs to — and by the time it is read, the modal that made the request may be shut.
 */
export function actionFailed(title: string, e: unknown): ActionFailure {
  return { kind: 'action', title, message: failureNotice(e), consequence: actionConsequence(e) };
}

/**
 * A failed action whose outcome the caller already knows and has written into `message`.
 *
 * <p>Two users, and neither has an exception to classify: input the surface rejected before
 * making any request at all, and a bulk delete, which succeeded for some objects and failed
 * for others and reports its own counts. Adding a general consequence line to either would be
 * a vaguer restatement of a sentence that is already exact, so it defaults to null.
 */
export function actionReport(title: string, message: string, consequence: string | null = null): ActionFailure {
  return { kind: 'action', title, message, consequence };
}

/**
 * The sign-in modal's rejection.
 *
 * <p>Its own builder because there is no request object to read: `LoginModal`'s submit
 * callback answers `false`, not a thrown 401. Nothing was attempted against the cluster, so
 * the third line says something else that is worth saying here and nowhere else: there is one
 * shared admin login (ADR-001), so a reader who is hunting for *their* account is looking for
 * something that does not exist. The re-try is the Sign in button, still on screen.
 */
export function signInRejected(): ActionFailure {
  return actionReport(
    'Sign in failed',
    'Those credentials were not accepted. Check them and sign in again.',
    'kweblens has a single shared admin login, not an account per person — there is no personal password to recover.',
  );
}

/**
 * A Helm dry run that did not render.
 *
 * <p>The one action failure with a consequence known for certain in the *other* direction:
 * `actionConsequence`'s hedge would be a false alarm here, because a dry run applies nothing
 * whatever the outcome. It still gets no Retry — what needs changing is the values or the
 * version in the form above, and re-posting the same document would be refused the same way.
 */
export function previewFailed(e: unknown): ActionFailure {
  return actionReport(
    'Dry-run failed',
    failureNotice(e),
    'Nothing was installed or upgraded — a dry run never applies anything. Change the values or the version above and preview again.',
  );
}

/**
 * A bulk delete that did not finish, given the line `runBulkDelete` already composed.
 *
 * <p>Not "failed": the message it wraps says "Deleted 7 of 10 …", and a heading that called
 * that a failure would contradict it. Some objects are gone and some are not, which is
 * precisely why nothing here offers to run it again.
 */
export function bulkDeleteIncomplete(message: string): ActionFailure {
  return actionReport('Delete did not finish', message);
}

/**
 * Whether this pane may offer to run it again by itself.
 *
 * <p>The whole rule in one predicate, so the two mixed panes cannot get the branch subtly
 * wrong: only a read. An action's re-do belongs to the control that started it.
 */
export function mayRetry(f: PaneFailure | null): boolean {
  return f !== null && f.kind === 'read';
}
