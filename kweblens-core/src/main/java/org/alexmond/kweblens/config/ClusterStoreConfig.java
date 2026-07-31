package org.alexmond.kweblens.config;

import java.nio.file.Path;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import lombok.extern.slf4j.Slf4j;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

import org.alexmond.kweblens.cluster.ClusterStore;
import org.alexmond.kweblens.cluster.FileClusterStore;
import org.alexmond.kweblens.cluster.InMemoryClusterStore;
import org.alexmond.kweblens.cluster.SecretClusterStore;

/**
 * Chooses the {@link ClusterStore} backend from {@code kweblens.cluster-store.mode}.
 *
 * <p>
 * {@code auto} — the default — resolves to Secrets when kweblens is running inside a
 * cluster and to the data directory otherwise, because those are the two deployments that
 * actually exist and each has exactly one sensible answer. In-cluster detection is the
 * presence of {@code KUBERNETES_SERVICE_HOST}, the same signal the fabric8 client itself
 * uses.
 *
 * <p>
 * Marked {@link ConditionalOnMissingBean} so an application embedding
 * {@code kweblens-core} can supply its own store (a database, a vault) without fighting
 * this one.
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
public class ClusterStoreConfig {

	@Bean
	@ConditionalOnMissingBean(ClusterStore.class)
	public ClusterStore clusterStore(ClusterStoreProperties properties) {
		ClusterStoreProperties.Mode mode = resolveMode(properties.getMode());
		return switch (mode) {
			case MEMORY -> new InMemoryClusterStore();
			case SECRET -> secretStore(properties);
			default -> new FileClusterStore(dataDirectory(properties));
		};
	}

	private ClusterStoreProperties.Mode resolveMode(ClusterStoreProperties.Mode configured) {
		if (configured != ClusterStoreProperties.Mode.AUTO) {
			return configured;
		}
		return inCluster() ? ClusterStoreProperties.Mode.SECRET : ClusterStoreProperties.Mode.FILE;
	}

	private boolean inCluster() {
		return StringUtils.hasText(System.getenv("KUBERNETES_SERVICE_HOST"));
	}

	/**
	 * The Secret store needs a client for the cluster kweblens is <em>running in</em>,
	 * which is not one of the clusters the registry owns. If that client cannot be built
	 * (no service account, no ambient config) fall back to the data directory rather than
	 * failing startup — a broken store must not take the whole app down.
	 */
	private ClusterStore secretStore(ClusterStoreProperties properties) {
		try {
			KubernetesClient client = new KubernetesClientBuilder().build();
			String namespace = StringUtils.hasText(properties.getNamespace()) ? properties.getNamespace()
					: client.getNamespace();
			if (!StringUtils.hasText(namespace)) {
				client.close();
				log.warn("Cannot determine kweblens's own namespace — falling back to the data directory "
						+ "for runtime clusters; set kweblens.cluster-store.namespace to use Secrets");
				return new FileClusterStore(dataDirectory(properties));
			}
			return new SecretClusterStore(client, namespace, properties.getSecretPrefix());
		}
		catch (RuntimeException ex) {
			log.warn("Could not build a client for kweblens's own cluster ({}) — runtime clusters "
					+ "will be persisted to the data directory instead", ex.getMessage());
			return new FileClusterStore(dataDirectory(properties));
		}
	}

	private Path dataDirectory(ClusterStoreProperties properties) {
		if (StringUtils.hasText(properties.getPath())) {
			return Path.of(properties.getPath());
		}
		return Path.of(System.getProperty("user.home", "."), ".kweblens", "clusters");
	}

}
