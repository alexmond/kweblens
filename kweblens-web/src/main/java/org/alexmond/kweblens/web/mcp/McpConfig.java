package org.alexmond.kweblens.web.mcp;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the {@link ClusterTools} @Tool methods with the MCP server so AI assistants
 * can call them over SSE (see {@code spring.ai.mcp.server.*} in application.yml).
 */
@Configuration
public class McpConfig {

	@Bean
	public ToolCallbackProvider clusterToolCallbacks(ClusterTools clusterTools) {
		return MethodToolCallbackProvider.builder().toolObjects(clusterTools).build();
	}

}
