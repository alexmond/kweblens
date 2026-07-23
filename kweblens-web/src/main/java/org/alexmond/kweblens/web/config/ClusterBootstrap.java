package org.alexmond.kweblens.web.config;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import org.alexmond.kweblens.cluster.ClusterRegistry;
import org.alexmond.kweblens.config.KweblensProperties;

/**
 * On startup, seeds the {@link ClusterRegistry} from the ambient kubeconfig so a
 * freshly-started server already shows the operator's current cluster as {@code default}.
 * Building the fabric8 client does not connect — the first API call is what reaches the
 * cluster — so a missing or unreachable kubeconfig does not block boot.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ClusterBootstrap implements ApplicationRunner {

	private final KweblensProperties properties;

	private final ClusterRegistry registry;

	@Override
	public void run(ApplicationArguments args) {
		if (!properties.isLoadKubeconfig()) {
			log.info("kweblens.load-kubeconfig=false — skipping ambient kubeconfig discovery");
			return;
		}
		try {
			KubernetesClient client = new KubernetesClientBuilder().build();
			registry.register("default", "Current kubeconfig", client);
		}
		catch (RuntimeException ex) {
			log.warn("Could not seed the ambient kubeconfig cluster: {}", ex.getMessage());
		}
	}

}
