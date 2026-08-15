package org.alexmond.kweblens.health;

import java.util.List;

/**
 * The whole answer an audit gives: what it found, and how much of it ran.
 *
 * <p>
 * The two are returned together because they are produced together and nothing downstream
 * can recompute the second from the first. A caller that reads only {@code findings} sees
 * a list that looks complete whether or not it is.
 *
 * @param findings what the scope is configured to permit, most severe first
 * @param incomplete the parts of the audit that did not fully run — empty on an audit
 * that saw everything, which is the normal case and must stay empty so a notice built
 * from this means something when it does appear
 */
public record SecurityAudit(List<SecurityFinding> findings, List<CoverageGap> incomplete) {
}
