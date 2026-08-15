package org.alexmond.kweblens.tui;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import org.alexmond.kweblens.cluster.ClusterRegistry;
import org.alexmond.kweblens.cluster.KubeconfigLoader;

/**
 * Registers one cluster per kubeconfig context, id = the context name — the same rule the
 * server's {@code ClusterBootstrap} uses, so a cluster is called the same thing in the
 * terminal as in the browser.
 *
 * <p>
 * What it deliberately does <b>not</b> do is the rest of the server's bootstrap: no
 * declared {@code kweblens.clusters[*]}, no restore of runtime-added clusters from a
 * store. A TUI runs as one operator on their own machine and is bounded by their own
 * kubeconfig and RBAC — that is the reason this surface is direct rather than via the
 * server, and inheriting a deployment's cluster list would quietly undo it.
 *
 * <p>
 * There is no {@code default} fallback either. {@code default} is only ever the server's
 * name for "no readable kubeconfig, use the ambient in-cluster config", which is not a
 * situation this binary is in; inventing the id here would produce a cluster whose name
 * tells the operator nothing. If there are no contexts, there are no clusters, and the
 * command says so.
 *
 * <p>
 * Building a fabric8 client does not connect, so registering every context reaches no
 * cluster and a stale or unreachable context costs nothing until it is used.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TuiClusterBootstrap {

	private final TuiProperties properties;

	private final ClusterRegistry registry;

	/**
	 * Register a cluster for every context in the kubeconfig.
	 * @return the ids registered by this call, in kubeconfig order; empty when kubeconfig
	 * loading is off, the file is unreadable, or it names no contexts
	 */
	public List<String> load() {
		if (!this.properties.isLoadKubeconfig()) {
			return List.of();
		}
		Path path = kubeconfigPath();
		if (path == null || !Files.isReadable(path)) {
			log.debug("No readable kubeconfig at {}", path);
			return List.of();
		}
		try {
			String yaml = Files.readString(path);
			List<String> contexts = KubeconfigLoader.contexts(yaml);
			for (String context : contexts) {
				if (this.registry.client(context).isEmpty()) {
					this.registry.register(context, context,
							KubeconfigLoader.clientFor(yaml, path.toString(), context));
				}
			}
			return contexts;
		}
		catch (IOException | RuntimeException ex) {
			log.warn("Could not read kubeconfig {}: {}", path, ex.getMessage());
			return List.of();
		}
	}

	/**
	 * The kubeconfig's {@code current-context}, or null when there is none to read. This
	 * is what makes {@code --context} optional without guessing: the context the
	 * operator's own {@code kubectl} would use is the one the TUI opens.
	 */
	public String currentContext() {
		Path path = kubeconfigPath();
		if (!this.properties.isLoadKubeconfig() || path == null || !Files.isReadable(path)) {
			return null;
		}
		try {
			return KubeconfigLoader.currentContext(Files.readString(path));
		}
		catch (IOException | RuntimeException ex) {
			log.debug("Could not read current-context from {}: {}", path, ex.getMessage());
			return null;
		}
	}

	private Path kubeconfigPath() {
		if (StringUtils.hasText(this.properties.getKubeconfig())) {
			return Path.of(this.properties.getKubeconfig());
		}
		String env = System.getenv("KUBECONFIG");
		if (StringUtils.hasText(env)) {
			// KUBECONFIG may be a path list; the first entry is the primary.
			return Path.of(env.split(File.pathSeparator)[0]);
		}
		String home = System.getProperty("user.home");
		return StringUtils.hasText(home) ? Path.of(home, ".kube", "config") : null;
	}

}
