package org.alexmond.kweblens.web.mcp;

import java.util.List;

import lombok.RequiredArgsConstructor;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import org.alexmond.kweblens.cluster.ClusterInfo;
import org.alexmond.kweblens.cluster.ClusterRegistry;
import org.alexmond.kweblens.resource.ResourceService;
import org.alexmond.kweblens.resource.ResourceSummary;

/**
 * Read-only Kubernetes tools exposed to AI assistants over MCP. Every method is a
 * projection of the same access layer the API and dashboard use — no mutation is offered.
 */
@Component
@RequiredArgsConstructor
public class ClusterTools {

	private final ClusterRegistry clusters;

	private final ResourceService resources;

	@Tool(description = "List the Kubernetes clusters kweblens is connected to.")
	public List<ClusterInfo> listClusters() {
		return clusters.list();
	}

	@Tool(description = "List the namespaces in a cluster, given its kweblens cluster id.")
	public List<ResourceSummary> listNamespaces(@ToolParam(description = "kweblens cluster id") String clusterId) {
		return resources.listNamespaces(clusterId);
	}

	@Tool(description = "List pods in a cluster, optionally scoped to one namespace.")
	public List<ResourceSummary> listPods(@ToolParam(description = "kweblens cluster id") String clusterId,
			@ToolParam(required = false, description = "namespace, or omit for all namespaces") String namespace) {
		return resources.listPods(clusterId, namespace);
	}

}
