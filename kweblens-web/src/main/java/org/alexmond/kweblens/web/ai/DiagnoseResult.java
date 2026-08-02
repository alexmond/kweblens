package org.alexmond.kweblens.web.ai;

import java.time.Instant;
import java.util.List;

/**
 * The result of a diagnosis run: the deterministic findings, plus whatever is known about
 * the optional LLM summary.
 *
 * <p>
 * <b>A read never produces a summary.</b> The findings are computed on every call — they
 * are cheap, grounded and need no key — while {@code summary} only ever comes from the
 * per-process {@link DiagnosisSummaryCache}, filled by an explicit {@code POST
 * .../diagnose/summary}. Everything else here exists so the panel can say what it is
 * showing rather than let an old reading pass as a current one.
 *
 * @param findings the findings, most severe first
 * @param summary an LLM-authored prioritized summary written about exactly these
 * findings, or null — which is the normal state, not a failure
 * @param aiEnriched whether a summary is being shown (equivalently,
 * {@code summary != null})
 * @param analysedAt when this scope was last analysed — the age of {@code summary} when
 * there is one, and otherwise how old the superseded reading was. Null if never
 * @param summaryOutdated true when this scope HAS been analysed but the findings have
 * changed since, so the old summary is deliberately withheld rather than shown as current
 * @param aiAvailable whether triggering an analysis could do anything here — AI is
 * enabled and a chat client is wired. False means the trigger should not be offered
 */
public record DiagnoseResult(List<Finding> findings, String summary, boolean aiEnriched, Instant analysedAt,
		boolean summaryOutdated, boolean aiAvailable) {
}
