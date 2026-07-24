package org.alexmond.kweblens.web.ai;

import java.util.List;

/**
 * The result of a diagnosis run: the findings (deterministic and, when enabled,
 * AI-added), an optional prioritized summary from the LLM, and whether the LLM actually
 * ran.
 *
 * @param findings the findings, most severe first
 * @param summary an LLM-authored prioritized summary, or null when AI is
 * disabled/unavailable
 * @param aiEnriched whether the LLM contributed
 */
public record DiagnoseResult(List<Finding> findings, String summary, boolean aiEnriched) {
}
