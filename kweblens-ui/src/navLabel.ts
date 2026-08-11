/**
 * Nav leaf labels that stay TELLABLE APART when the rail runs out of room (#327).
 *
 * <p>The rail ellipsises a leaf label at its end, which is the right default and exactly the
 * wrong one for Kubernetes kinds: they are built by suffixing, so a pair of siblings shares a
 * long prefix and differs only in the tail — `ValidatingAdmissionPolicy` /
 * `ValidatingAdmissionPolicyBinding`, `VerticalPodAutoscaler` /
 * `VerticalPodAutoscalerCheckpoint`. Cutting the tail cuts off precisely the part that says
 * which one you are looking at, and two adjacent rows then render the same string. That is not
 * a cosmetic clip: the reader cannot tell them apart and clicking one is a guess.
 *
 * <p>So the split is computed over the SET, the way {@link tileLabels} computes the cluster
 * rail's two-letter tiles: uniqueness is a property of the set, never of one label read alone.
 * A label at risk of colliding is cut into a `head` the ellipsis may eat and a `tail` that must
 * survive; the rendering puts them in two flex items and lets the browser do the fitting, so
 * nothing here has to guess a glyph width (that guess was 20% out the one time it was tried).
 * When the whole label fits, the two halves sit flush and the split is invisible.
 *
 * <p>Only labels at risk are split. A tail-ellipsis keeps the scannable prefix and is what the
 * reader expects, so it stays the default everywhere a collision cannot happen — the same
 * reasoning as `tileLabels` only relabelling the ids that actually collide.
 */
export interface NavLabelParts {
  /** What the ellipsis is allowed to eat. Empty when the label needs no protection. */
  head: string;
  /** What must survive: the part that tells this leaf from its siblings. */
  tail: string;
}

/** Characters a tail may not start on — the separator belongs to the head that precedes it. */
const SEPARATOR = /[\s\-_./]/;
const isLower = (c: string): boolean => /[a-z0-9]/.test(c);
const isUpper = (c: string): boolean => /[A-Z]/.test(c);

/**
 * How many leading characters two siblings must share before their tails are protected.
 *
 * <p>Chosen so the rule is sound rather than merely plausible. Two labels can only render the
 * same string if they share a prefix at least as long as the number of characters that fit —
 * call it `k`. The gate below fires whenever the shared prefix reaches `min(12, half the
 * shorter label)`, so it can only miss a real collision when `k` is under 12 characters, i.e.
 * when the rail is too narrow to read anything anyway. Erring the other way is free: a split
 * on a label that never truncates renders identically to no split at all.
 */
const PROTECT_CHARS = 12;

/** Offsets at which a tail may begin: after a separator, or at a camel-case hump. */
function tailStarts(label: string): number[] {
  const at: number[] = [];
  for (let i = 1; i < label.length; i += 1) {
    const prev = label[i - 1];
    const ch = label[i];
    const next = label[i + 1] ?? '';
    if (SEPARATOR.test(ch)) {
      continue;
    }
    // `Ingress Classes` -> `Classes`; `HelmChartConfig` -> `Config`; `IPAddressPool` ->
    // `AddressPool` (an acronym run ends where the next word's lower-case starts).
    if (SEPARATOR.test(prev) || (isLower(prev) && isUpper(ch)) || (isUpper(prev) && isUpper(ch) && isLower(next))) {
      at.push(i);
    }
  }
  return at;
}

function commonPrefixLength(a: string, b: string): number {
  let n = 0;
  while (n < a.length && n < b.length && a[n] === b[n]) {
    n += 1;
  }
  return n;
}

/** Does some sibling share enough of this label's start that truncation could merge them? */
function atRiskOfColliding(label: string, others: string[]): boolean {
  return others.some((other) => {
    const shared = commonPrefixLength(label, other);
    const shorter = Math.min(label.length, other.length);
    return shared > 0 && shared >= Math.min(PROTECT_CHARS, shorter / 2);
  });
}

/**
 * Split each label of one rendered sibling list into an elidable head and a protected tail.
 *
 * <p>The result is index-aligned with the input. A label that cannot collide — or that has no
 * tail able to distinguish it — comes back as `{ head: '', tail: label }`, which renders
 * exactly as an unsplit label does.
 *
 * <p>The tail chosen is the SHORTEST one that is not also the ending of another sibling, so as
 * much of the head as possible stays readable. Rejecting a tail that another sibling also ends
 * with is what makes the rendered `…tail` unique: `Cluster Role Bindings` cannot keep
 * `Bindings`, because `Role Bindings` sits two rows below it and ends the same way. When no
 * such tail exists the label is left unsplit rather than protected with an ambiguous tail —
 * refusing to promise a distinction that is not there.
 */
export function navLabelParts(labels: string[]): NavLabelParts[] {
  return labels.map((label, i) => {
    const others = labels.filter((_, j) => j !== i);
    if (!atRiskOfColliding(label, others)) {
      return { head: '', tail: label };
    }
    // tailStarts() ascends, so reversing walks the candidate tails shortest-first.
    for (const start of tailStarts(label).reverse()) {
      const tail = label.slice(start);
      if (others.every((other) => !other.endsWith(tail))) {
        return { head: label.slice(0, start), tail };
      }
    }
    return { head: '', tail: label };
  });
}
