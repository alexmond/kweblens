package org.alexmond.kweblens.log;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer;
import org.junit.jupiter.api.Test;

import org.alexmond.kweblens.cluster.ClusterRegistry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The timestamped and previous-run log reads that multi-source following depends on. Both
 * assert the exact request kweblens makes, because the DSL's builder order decides which
 * query parameters the API server actually receives — getting it wrong silently returns
 * an untimestamped (and therefore unsortable) stream.
 */
@EnableKubernetesMockClient(crud = true)
class LogServiceTimestampsTest {

	KubernetesClient client;

	KubernetesMockServer server;

	private LogService serviceFor(String clusterId) {
		ClusterRegistry registry = new ClusterRegistry();
		registry.register(clusterId, clusterId, client);
		return new LogService(registry);
	}

	@Test
	void tailWithTimestampsAsksForTimestampsAndReturnsThem() {
		server.expect()
			.get()
			.withPath("/api/v1/namespaces/web/pods/nginx/log?pretty=false&container=app&tailLines=2&timestamps=true")
			.andReturn(200, "2026-07-29T12:00:00Z hello\n2026-07-29T12:00:01Z world")
			.always();

		String log = serviceFor("c1").tailWithTimestamps("c1", "web", "nginx", "app", 2);

		assertThat(log).contains("2026-07-29T12:00:00Z hello").contains("2026-07-29T12:00:01Z world");
	}

	@Test
	void previousReadsTheTerminatedInstancesLog() {
		server.expect()
			.get()
			.withPath("/api/v1/namespaces/web/pods/nginx/log?pretty=false&container=app&previous=true&tailLines=5")
			.andReturn(200, "panic: out of memory")
			.always();

		assertThat(serviceFor("c1").previous("c1", "web", "nginx", "app", 5)).isEqualTo("panic: out of memory");
	}

	@Test
	void previousReturnsNullWhenThereIsNoPreviousRun() {
		// The API server 400s for a container that has never restarted. That is the
		// normal
		// "nothing to show" case, not a failure worth propagating to the UI.
		server.expect()
			.get()
			.withPath("/api/v1/namespaces/web/pods/fresh/log?pretty=false&previous=true&tailLines=5")
			.andReturn(400, "previous terminated container not found")
			.always();

		assertThat(serviceFor("c1").previous("c1", "web", "fresh", null, 5)).isNull();
	}

	@Test
	void treatsARefusedLogAsAFailureRatherThanAsOutput() {
		// The API server answers 400 with a Status body when a container has not started,
		// and fabric8 hands that JSON back as though the container had PRINTED it. During
		// a
		// rollout that happens for every new pod, so left alone the workload appears to
		// log
		// a Kubernetes error object — a fabricated line attributed to the user's app.
		server.expect()
			.get()
			.withPath("/api/v1/namespaces/web/pods/starting/log?pretty=false&container=app&tailLines=5&timestamps=true")
			.andReturn(200, """
					{"kind":"Status","apiVersion":"v1","metadata":{},"status":"Failure",\
					"message":"container \\"app\\" in pod \\"starting\\" is waiting to start: ContainerCreating",\
					"reason":"BadRequest","code":400}""")
			.always();

		assertThatThrownBy(() -> serviceFor("c1").tailWithTimestamps("c1", "web", "starting", "app", 5))
			.isInstanceOf(LogUnavailableException.class)
			.hasMessageContaining("waiting to start");
	}

	@Test
	void doesNotMistakeAnAppsOwnJsonLoggingForAFailure() {
		// Plenty of apps log structured JSON. Only a Status/Failure envelope is a
		// refusal.
		server.expect()
			.get()
			.withPath("/api/v1/namespaces/web/pods/json/log?pretty=false&container=app&tailLines=1&timestamps=true")
			.andReturn(200, "2026-07-29T12:00:00Z {\"level\":\"info\",\"status\":\"Failure\",\"msg\":\"retrying\"}")
			.always();

		assertThat(serviceFor("c1").tailWithTimestamps("c1", "web", "json", "app", 1)).contains("retrying");
	}

}
