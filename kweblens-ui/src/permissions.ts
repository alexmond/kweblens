/**
 * What the deployment's service account may do here — and, far more importantly, what to do
 * when we could not find out.
 *
 * <p>The server asks the cluster with a `SelfSubjectAccessReview` (see `AccessReviewService`).
 * Per ADR-001 kweblens runs as ONE shared operator identity, so the answer is about **the
 * service account this deployment runs as**, never about the person at the keyboard — there
 * is no "you" here, and every sentence this module writes says so.
 *
 * <p><b>This is an affordance, not a gate.</b> Nothing in this file may be consulted to decide
 * whether a request is sent; it decides whether a control looks available and what is written
 * next to it when it does not. The write is still refused server-side if it is asked for
 * anyway.
 *
 * <p><b>Three states, and `unknown` renders as ENABLED.</b> A review that errored, timed out,
 * was itself forbidden, or was never asked has told us nothing, and a control greyed out by a
 * failed probe is a lie about the cluster. `denied` is the ONLY verdict that disables
 * anything, which is why {@link isDenied} tests for it positively rather than testing for "not
 * allowed" — the second spelling silently swallows `unknown` and is the exact inversion this
 * whole feature is written to prevent.
 */
import type { KindAccess, Verdict } from './types';

/** The verbs the server reviews for a surface. One review each — never one per row. */
export type AccessVerb = 'create' | 'patch' | 'delete';

/**
 * The verdict for one verb.
 *
 * `'unknown'` for every absent case, and the absent cases are real: no review has loaded yet,
 * the request failed, the server did not ask about this verb, or the action is not gated on a
 * verb this report covers. Typed honestly — `access` really can be null, and a type that said
 * otherwise would pass `vue-tsc` and throw at runtime.
 */
export function verdictFor(access: KindAccess | null | undefined, verb: AccessVerb | null | undefined): Verdict {
  if (!access || !verb) {
    return 'unknown';
  }
  return access.verbs[verb]?.verdict ?? 'unknown';
}

/** Whether a control should be disabled. Only a real refusal qualifies. */
export function isDenied(access: KindAccess | null | undefined, verb: AccessVerb | null | undefined): boolean {
  return verdictFor(access, verb) === 'denied';
}

/** The verbs in English, for the sentence below. */
const VERB_WORDS: Record<AccessVerb, string> = {
  create: 'create',
  patch: 'change',
  delete: 'delete',
};

/**
 * Why a control is greyed out, in one sentence.
 *
 * Names the service account and not the operator, because with one shared identity "you cannot
 * delete Pods" would be a claim about a person the product does not model. Names the scope too:
 * a refusal in `ns1` is not a refusal everywhere, and a reason that omits the namespace reads as
 * though it were.
 *
 * The cluster's own words are appended when it gave any ("RBAC: no rules authorize this") — that
 * sentence is often the whole answer, and throwing it away leaves the reader guessing at which
 * role is missing.
 */
export function deniedReason(access: KindAccess | null | undefined, verb: AccessVerb | null | undefined): string {
  const word = verb ? VERB_WORDS[verb] : 'change';
  const kind = access?.kind ?? 'this kind';
  const where = access?.namespace ? ` in ${access.namespace}` : '';
  const said = verb ? access?.verbs[verb]?.reason : null;
  const sentence = `The service account this deployment of kweblens runs as cannot ${word} ${kind}${where}.`;
  return said ? `${sentence} The cluster said: ${said}` : sentence;
}

/**
 * A control's state, ready to bind: whether to disable it and what to say if so.
 *
 * One function rather than two calls at each site, so no surface can disable a control and
 * forget to say why — a dead button with no explanation is the defect this ticket exists to
 * remove, not a smaller version of it.
 */
export interface ControlAccess {
  disabled: boolean;
  reason: string | null;
}

export function controlAccess(
  access: KindAccess | null | undefined,
  verb: AccessVerb | null | undefined,
): ControlAccess {
  return isDenied(access, verb)
    ? { disabled: true, reason: deniedReason(access, verb) }
    : { disabled: false, reason: null };
}
