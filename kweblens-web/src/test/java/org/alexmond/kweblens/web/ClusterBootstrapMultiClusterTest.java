package org.alexmond.kweblens.web;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import org.alexmond.kweblens.cluster.ClusterInfo;
import org.alexmond.kweblens.cluster.ClusterRegistry;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies {@code kweblens.clusters[*]} are registered on boot from their kubeconfig +
 * context. Ambient discovery is off so the only cluster is the configured one
 * (deterministic).
 */
@SpringBootTest(properties = "kweblens.load-kubeconfig=false")
class ClusterBootstrapMultiClusterTest {

	private static final String KUBECONFIG = """
			apiVersion: v1
			kind: Config
			current-context: dev
			clusters:
			- name: prod-cluster
			  cluster:
			    server: https://prod.example:6443
			contexts:
			- name: prod
			  context:
			    cluster: prod-cluster
			    user: prod-user
			users:
			- name: prod-user
			  user: {}
			""";

	@DynamicPropertySource
	static void configuredCluster(DynamicPropertyRegistry registry) throws IOException {
		Path kubeconfig = Files.createTempFile("kweblens-kubeconfig", ".yaml");
		Files.writeString(kubeconfig, KUBECONFIG);
		kubeconfig.toFile().deleteOnExit();
		registry.add("kweblens.clusters[0].id", () -> "prod");
		registry.add("kweblens.clusters[0].name", () -> "Production");
		registry.add("kweblens.clusters[0].kubeconfig", kubeconfig::toString);
		registry.add("kweblens.clusters[0].context", () -> "prod");
	}

	@Autowired
	private ClusterRegistry clusters;

	@Test
	void registersConfiguredClusterFromKubeconfig() {
		assertThat(clusters.info("prod")).get().satisfies((info) -> {
			assertThat(info.name()).isEqualTo("Production");
			assertThat(info.masterUrl()).contains("prod.example:6443");
		});
		assertThat(clusters.list()).extracting(ClusterInfo::id).containsExactly("prod");
	}

}
