package org.alexmond.kweblens.health;

/**
 * <b>How much of a check ran</b> — a claim about the LIST, not about any one object in
 * it.
 *
 * <p>
 * This is deliberately not a severity. {@code critical} / {@code warning} / {@code info}
 * grade how bad a finding is; "the audit stopped at twenty" and "the bindings could not
 * be listed" grade how much of the audit happened, which is a different axis and belongs
 * beside the count of findings rather than inside it (#388). Filing them as {@code info}
 * findings put the admission that a list was partial somewhere in the scroll of that
 * list, where the reader who most needs it — the one reading the header — never meets it.
 *
 * <p>
 * <b>Emitted only by the code that knows.</b> A gap is produced at the point the check
 * gave up, so a consumer never has to infer one by matching a finding's title — that
 * would be a second copy of a server rule, and it would go stale silently the first time
 * the wording changed.
 *
 * <p>
 * The matching finding stays in the list. An operator reading or exporting the findings
 * should still meet the reason next to the objects it concerns; what a gap adds is the
 * summary, not the detail. So this carries no object, no evidence and nothing read out of
 * a Secret — two short strings a reader can act on.
 *
 * @param dimension which part of the check fell short, e.g. {@code container privileges}
 * @param reason why it fell short, as a sentence. Deterministic: two reads of an
 * unchanged cluster must produce the same text, because the response they belong to is
 * asserted byte-identical
 */
public record CoverageGap(String dimension, String reason) {
}
