package org.alexmond.kweblens.web.sim;

import java.util.HashMap;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.KubernetesCrudDispatcher;
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer;
import io.fabric8.mockwebserver.Context;
import io.fabric8.mockwebserver.MockWebServer;
import lombok.extern.slf4j.Slf4j;

import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import org.alexmond.kweblens.cluster.ClusterRegistry;

/**
 * Wires the built-in cluster simulator when {@code kweblens.simulator.enabled=true}:
 * starts an in-JVM fabric8 crud mock API server, seeds it with configurable object counts
 * (see {@link SimulatorSeeder}), and registers it into the {@link ClusterRegistry} as a
 * normal cluster. Because it is a real {@link KubernetesClient}, every code path — list,
 * the on-connect watch replay-burst, counts, YAML — runs unchanged, which is what makes
 * it useful for performance testing without a live cluster. Inert unless enabled.
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "kweblens.simulator", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(SimulatorProperties.class)
public class SimulatorConfig {

	@Bean(destroyMethod = "destroy")
	KubernetesMockServer simMockServer() {
		KubernetesMockServer server = new KubernetesMockServer(new Context(), new MockWebServer(), new HashMap<>(),
				new KubernetesCrudDispatcher(), false);
		server.init();
		return server;
	}

	@Bean
	ApplicationRunner simSeeder(KubernetesMockServer server, SimulatorProperties props, ClusterRegistry registry) {
		return (args) -> {
			KubernetesClient client = server.createClient();
			SimulatorSeeder.seed(client, props);
			registry.register(props.getClusterId(), "Simulator", client);
			log.info("Simulator cluster '{}' registered: {} objects/kind across {} namespaces", props.getClusterId(),
					props.getSize(), props.getNamespaces());
		};
	}

}
