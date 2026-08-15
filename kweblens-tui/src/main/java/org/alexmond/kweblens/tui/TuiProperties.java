package org.alexmond.kweblens.tui;

import lombok.Data;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How the TUI finds its clusters and how much it fetches at a time.
 *
 * <p>
 * Deliberately its own prefix rather than reusing {@code kweblens.*}: the server's
 * settings are about a shared deployment (which clusters it declares, where it persists
 * runtime ones, what it exposes), and none of that applies to a binary that runs as one
 * operator on their own machine with their own kubeconfig.
 */
@Data
@ConfigurationProperties(prefix = "kweblens.tui")
public class TuiProperties {

	/**
	 * Whether to register a cluster for every context in the ambient kubeconfig. Off is
	 * what a test wants — it starts the registry empty so the test seeds its own client
	 * and cannot reach a real cluster, exactly as {@code kweblens.load-kubeconfig=false}
	 * does for the web tests.
	 */
	private boolean loadKubeconfig = true;

	/**
	 * The kubeconfig to read, overriding {@code KUBECONFIG} and {@code ~/.kube/config}.
	 * Mostly for tests; an operator sets {@code KUBECONFIG}.
	 */
	private String kubeconfig;

	/**
	 * Objects per list request. Lists are always fetched in pages — one unchunked list of
	 * a big kind is ~241 KB of transient heap per object against a live cluster
	 * (#292/#293), and a terminal has no more heap than a browser. Zero or less asks for
	 * the whole collection in one request, which is the escape hatch if an API server
	 * mishandles continue tokens.
	 */
	private int chunkSize = 500;

}
