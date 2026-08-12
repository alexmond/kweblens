package org.alexmond.kweblens.health;

/**
 * One object's state, in the same vocabulary an overview card counts.
 *
 * <p>
 * This is the row-shaped half of {@link StateCount}: a card says
 * {@code StateCount("Pending", 3, "warn")}, and the three objects behind that number each
 * carry {@code ObjectState("Pending", "warn")}. The two are produced by the same call for
 * the same object (see {@link StatusVocabulary}), which is what makes "click the 3 and
 * see exactly those three" a property of the code rather than a hope.
 *
 * @param label the state's name in the kind's own vocabulary — "Running", "Unavailable",
 * "CrashLoopBackOff". Identical, character for character, to the {@code label} of the
 * {@link StateCount} this object was counted into; a filter matches on it exactly, so a
 * different spelling here would be a different state.
 * @param tone how to colour it: {@link StateCount#OK}, {@link StateCount#WARN},
 * {@link StateCount#ERR} or {@link StateCount#IDLE}.
 */
public record ObjectState(String label, String tone) {
}
