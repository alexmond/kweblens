package org.alexmond.kweblens.web.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import org.alexmond.kweblens.cluster.ClusterConfigService;
import org.alexmond.kweblens.cluster.ClusterRegistry;
import org.alexmond.kweblens.cluster.KubeconfigLoader;
import org.alexmond.kweblens.config.KweblensProperties;

/**
 * On startup, seeds the {@link ClusterRegistry}: first every explicitly-configured
 * {@code kweblens.clusters[*]}, then — when {@code kweblens.load-kubeconfig} is on —
 * every context in the ambient kubeconfig (each becomes its own cluster), and finally
 * every cluster added at runtime and persisted by the
 * {@link org.alexmond.kweblens.cluster.ClusterStore}. Building a fabric8 client does not
 * connect, so a missing/unreachable kubeconfig never blocks boot.
 *
 * <p>
 * Runtime clusters are restored <b>last and never overwrite</b> a declared id: the
 * deployment manifest is the authority on the clusters it names, and a persisted entry
 * silently shadowing one would make the running state disagree with the manifest with
 * nothing in the UI to explain why.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ClusterBootstrap implements ApplicationRunner {

	private final KweblensProperties properties;

	private final ClusterRegistry registry;

	private final ClusterConfigService clusterConfig;

	@Override
	public void run(ApplicationArguments args) {
		properties.getClusters().forEach(this::registerConfigured);
		if (properties.isLoadKubeconfig()) {
			loadAmbientContexts();
		}
		int restored = this.clusterConfig.restore();
		if (restored > 0) {
			log.info("Restored {} runtime-configured cluster(s)", restored);
		}
		if (registry.list().isEmpty()) {
			log.info("No clusters registered — set kweblens.clusters[*] or provide a kubeconfig.");
		}
	}

	private void registerConfigured(KweblensProperties.Cluster cluster) {
		String id = cluster.getId();
		if (!StringUtils.hasText(id)) {
			log.warn("Skipping a kweblens.clusters entry with no id");
			return;
		}
		String name = StringUtils.hasText(cluster.getName()) ? cluster.getName() : id;
		try {
			KubernetesClient client;
			if (StringUtils.hasText(cluster.getKubeconfig())) {
				Path path = Path.of(cluster.getKubeconfig());
				client = KubeconfigLoader.clientFor(Files.readString(path), path.toString(), cluster.getContext());
			}
			else {
				client = new KubernetesClientBuilder().build();
			}
			registry.register(id, name, client);
		}
		catch (IOException | RuntimeException ex) {
			log.warn("Could not register configured cluster '{}': {}", id, ex.getMessage());
		}
	}

	private void loadAmbientContexts() {
		Path path = ambientKubeconfigPath();
		if (path == null || !Files.isReadable(path)) {
			seedDefaultClient();
			return;
		}
		try {
			String yaml = Files.readString(path);
			List<String> contexts = KubeconfigLoader.contexts(yaml);
			if (contexts.isEmpty()) {
				seedDefaultClient();
				return;
			}
			for (String context : contexts) {
				if (registry.client(context).isEmpty()) {
					registry.register(context, context, KubeconfigLoader.clientFor(yaml, path.toString(), context));
				}
			}
		}
		catch (IOException | RuntimeException ex) {
			log.warn("Could not read ambient kubeconfig {}: {}", path, ex.getMessage());
			seedDefaultClient();
		}
	}

	private void seedDefaultClient() {
		if (registry.client("default").isPresent()) {
			return;
		}
		try {
			registry.register("default", "Current kubeconfig", new KubernetesClientBuilder().build());
		}
		catch (RuntimeException ex) {
			log.warn("Could not seed the ambient kubeconfig cluster: {}", ex.getMessage());
		}
	}

	private Path ambientKubeconfigPath() {
		String env = System.getenv("KUBECONFIG");
		if (StringUtils.hasText(env)) {
			// KUBECONFIG may be a path list; the first entry is the primary.
			return Path.of(env.split(java.io.File.pathSeparator)[0]);
		}
		String home = System.getProperty("user.home");
		return StringUtils.hasText(home) ? Path.of(home, ".kube", "config") : null;
	}

}
