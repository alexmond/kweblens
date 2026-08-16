package org.alexmond.kweblens.tui.data;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer;

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
import org.alexmond.kweblens.resource.ApiDiscoveryService;
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

	private static final String CORE_V1 = resourceList("v1",
			resource("pods", "pod", "Pod", true, "\"po\"") + ","
					+ resource("namespaces", "namespace", "Namespace", false, "\"ns\"") + ","
					+ resource("configmaps", "configmap", "ConfigMap", true, "\"cm\"") + ","
					+ resource("events", "event", "Event", true, "\"ev\""));

	private static final String APPS_V1 = resourceList("apps/v1",
			resource("deployments", "deployment", "Deployment", true, "\"deploy\""));

	private static final String TRAEFIK = resourceList("traefik.io/v1alpha1",
			resource("ingressroutes", "ingressroute", "IngressRoute", true, ""));

	private static final String GROUPS = "{\"kind\":\"APIGroupList\",\"groups\":[" + group("apps", "v1") + ","
			+ group("traefik.io", "v1alpha1") + "]}";

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
		return new CoreClusterDataSource(registry, new ApiDiscoveryService(registry), resources,
				new ObjectStates(contexts), new LogService(registry), new ExecService(registry));
	}

	public static CoreClusterDataSource dataSource(KubernetesClient client) {
		return dataSource(registry(client));
	}

	/**
	 * Serve API discovery from the mock server: the core group, {@code apps}, and one CRD
	 * group.
	 *
	 * <p>
	 * <b>The CRUD dispatcher does not answer {@code /api/v1} or {@code /apis}</b> — it
	 * serves objects, not the catalogue of what kinds exist — so a test that wants the
	 * command line to resolve anything has to say what the API server publishes. Writing
	 * it out here rather than seeding objects is also the honest shape: discovery is a
	 * document, and every alias in it is either in this text or is not answered.
	 * {@code traefik.io/ingressroutes} is in it on purpose: it is a kind no catalog in
	 * this repo lists, so a test that resolves it proves resolution came from discovery.
	 */
	public static void stubDiscovery(KubernetesMockServer server) {
		server.expect().get().withPath("/api/v1").andReturn(200, CORE_V1).always();
		server.expect().get().withPath("/apis").andReturn(200, GROUPS).always();
		server.expect().get().withPath("/apis/apps/v1").andReturn(200, APPS_V1).always();
		server.expect().get().withPath("/apis/traefik.io/v1alpha1").andReturn(200, TRAEFIK).always();
	}

	private static String resourceList(String groupVersion, String resources) {
		return "{\"kind\":\"APIResourceList\",\"groupVersion\":\"" + groupVersion + "\",\"resources\":[" + resources
				+ "]}";
	}

	private static String resource(String plural, String singular, String kind, boolean namespaced, String shortNames) {
		return "{\"name\":\"" + plural + "\",\"singularName\":\"" + singular + "\",\"namespaced\":" + namespaced
				+ ",\"kind\":\"" + kind + "\",\"verbs\":[\"list\",\"get\",\"watch\"],\"shortNames\":[" + shortNames
				+ "]}";
	}

	private static String group(String name, String version) {
		return "{\"name\":\"" + name + "\",\"versions\":[{\"groupVersion\":\"" + name + "/" + version
				+ "\",\"version\":\"" + version + "\"}],\"preferredVersion\":{\"groupVersion\":\"" + name + "/"
				+ version + "\",\"version\":\"" + version + "\"}}";
	}

}
