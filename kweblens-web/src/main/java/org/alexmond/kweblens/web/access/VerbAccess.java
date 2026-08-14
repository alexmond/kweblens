package org.alexmond.kweblens.web.access;

import java.util.Locale;

import org.alexmond.kweblens.access.AccessAnswer;

/**
 * One verb's verdict on the wire.
 *
 * <p>
 * {@code verdict} is the lower-cased {@code AccessVerdict} — {@code allowed},
 * {@code denied} or {@code unknown} — so the SPA's union type reads as three plain
 * strings. <b>Three, not two</b>: {@code unknown} is what a failed or unavailable review
 * returns and the client renders it as <i>enabled</i>. Collapsing it into either real
 * answer is the defect the tri-state exists to prevent.
 *
 * @param verdict {@code allowed} / {@code denied} / {@code unknown}
 * @param reason the cluster's own words when it gave any, or why the answer is unknown;
 * {@code null} when there is nothing to add. Typed nullable because it genuinely is — an
 * allow carries no reason
 */
public record VerbAccess(String verdict, String reason) {

	/** Project a core answer onto the wire. */
	public static VerbAccess of(AccessAnswer answer) {
		return new VerbAccess(answer.verdict().name().toLowerCase(Locale.ROOT), answer.reason());
	}

}
