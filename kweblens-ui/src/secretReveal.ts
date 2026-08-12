/**
 * Which masked values the reader has chosen to see, tracked by ROW INDEX.
 *
 * A Secret's values are masked by default and only an explicit act may unmask one, so every
 * operation that renumbers the rows has to renumber this set with them. The failure mode is not
 * a cosmetic glitch: an index left behind points at whatever row slid into that position, so the
 * reader removes one secret and a DIFFERENT one appears in clear text. That is why this is a
 * module with tests rather than a `Set.delete` inside a click handler.
 *
 * Every function returns a NEW set. The editor holds the set in a `ref`, and mutating it in
 * place does not re-render — the previous version deleted from it directly and the mask did not
 * come back until something else happened to touch the component.
 */

/** Reveal or re-mask one row. */
export function toggleReveal(revealed: ReadonlySet<number>, index: number): Set<number> {
  const next = new Set(revealed);
  if (!next.delete(index)) {
    next.add(index);
  }
  return next;
}

/**
 * Row `index` has been removed: drop it, and pull every later index down by one to follow the
 * row it belongs to.
 */
export function afterRemove(revealed: ReadonlySet<number>, index: number): Set<number> {
  return new Set([...revealed].filter((r) => r !== index).map((r) => (r > index ? r - 1 : r)));
}

/**
 * The rows have been re-seeded from a new model, so the old indices name nothing.
 *
 * This deliberately does not try to carry reveal state across by key. Re-masking everything is
 * the direction that cannot leak, and the reader's own Show button is one click away.
 */
export function afterReseed(): Set<number> {
  return new Set();
}
