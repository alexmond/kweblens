package org.alexmond.kweblens.tui.data;

import io.fabric8.kubernetes.client.KubernetesClient;

import org.alexmond.kweblens.cluster.ClusterRegistry;
import org.alexmond.kweblens.exec.ExecService;
import org.alexmond.kweblens.health.ConfigUsageService;
import org.alexmond.kweblens.health.NetworkHealthService;
import org.alexmond.kweblens.health.ObjectStates;
import org.alexmond.kweblens.health.StatusContexts;
import org.alexmond.kweblens.health.StorageHealthService;
import org.alexmond.kweblens.log.LogService;
import org.alexmond.kweblens.metric.MetricsProperties;
import org.alexmond.kweblens.metric.PrometheusMetricService;
import org.alexmond.kweblens.resource.ResourceService;

/**
 * The {@code kweblens-core} object graph the adapter sits on, wired by hand around a mock
 * client — the same shape the Spring context builds, without a context or a cluster.
 *
 * <p>
 * Written out rather than {@code @MockBean}-ed because the point of these tests is that
 * {@link CoreClusterDataSource} is nothing but delegation to <em>the real</em> core
 * services: a mocked core would pass whatever the adapter did.
 */
public final class CoreStack {

	/** The cluster id every test in this package registers its mock client under. */
	public static final String CLUSTER = "mock";

	private CoreStack() {
	}

	public static ClusterRegistry registry(KubernetesClient client) {
		ClusterRegistry registry = new ClusterRegistry();
		registry.register(CLUSTER, CLUSTER, client);
		return registry;
	}

	public static CoreClusterDataSource dataSource(ClusterRegistry registry) {
		ResourceService resources = new ResourceService(registry);
		PrometheusMetricService metrics = new PrometheusMetricService(registry, new MetricsProperties());
		StatusContexts contexts = new StatusContexts(new NetworkHealthService(registry, resources),
				new StorageHealthService(resources, metrics), new ConfigUsageService(registry, resources));
		return new CoreClusterDataSource(registry, resources, new ObjectStates(contexts), new LogService(registry),
				new ExecService(registry));
	}

	public static CoreClusterDataSource dataSource(KubernetesClient client) {
		return dataSource(registry(client));
	}

}
