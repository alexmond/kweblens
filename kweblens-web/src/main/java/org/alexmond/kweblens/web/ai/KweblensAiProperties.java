package org.alexmond.kweblens.web.ai;

import lombok.Data;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings for AI-assisted diagnosis. Deterministic validators always run; the LLM only
 * enriches findings when {@code enabled} is true and an Anthropic API key is configured.
 */
@Data
@ConfigurationProperties(prefix = "kweblens.ai")
public class KweblensAiProperties {

	/** Enable LLM enrichment of diagnosis (off by default). */
	private boolean enabled;

}
