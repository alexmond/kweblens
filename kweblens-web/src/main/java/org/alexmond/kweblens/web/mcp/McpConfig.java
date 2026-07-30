package org.alexmond.kweblens.web.mcp;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the @Tool methods with the MCP server so AI assistants can call them over SSE
 * (see {@code spring.ai.mcp.server.*} in application.yml).
 *
 * <p>
 * Three objects rather than one because the surface grew past what one class should hold:
 * orientation ({@link ClusterTools}), evidence ({@link DiagnosticTools}) and verdicts
 * ({@link HealthTools}). They are registered together — a partial surface is worse than
 * none, since an assistant that can list pods but not read their previous logs will
 * confidently diagnose a crashloop it has no evidence for.
 */
@Configuration
public class McpConfig {

	@Bean
	public ToolCallbackProvider clusterToolCallbacks(ClusterTools clusterTools, DiagnosticTools diagnosticTools,
			HealthTools healthTools) {
		return MethodToolCallbackProvider.builder().toolObjects(clusterTools, diagnosticTools, healthTools).build();
	}

}
