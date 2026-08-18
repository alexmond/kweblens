package org.alexmond.kweblens.tui.data;

import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The three log methods of the port, against the in-JVM API server.
 *
 * <p>
 * The adapter is nothing but delegation, so what these check is that it delegates to
 * <b>the right thing</b>: the containers come from {@code LogSourceResolver} — the SPA's
 * own expansion of "logs for this pod" — rather than from a walk of {@code spec} written
 * a second time here, and the timestamped follow goes through
 * {@code LogService.watchWithTimestamps} rather than through the plain one with a flag
 * bolted on.
 *
 * <p>
 * The release itself is {@code CoreSessionTest}, which is where a stream can be watched
 * being closed.
 */
@EnableKubernetesMockClient(crud = true)
class CoreClusterDataSourceLogTest {

	private static final String PREVIOUS_PATH = "/api/v1/namespaces/web/pods/nginx/log"
			+ "?pretty=false&container=app&previous=true&tailLines=100";

	KubernetesClient client;

	KubernetesMockServer server;

	private void seedPod(String... containers) {
		PodBuilder pod = new PodBuilder().withNewMetadata()
			.withNamespace("web")
			.withName("nginx")
			.endMetadata()
			.withNewSpec()
			.endSpec();
		for (String container : containers) {
			pod = pod.editSpec().addNewContainer().withName(container).withImage("busybox").endContainer().endSpec();
		}
		this.client.pods().inNamespace("web").resource(pod.build()).create();
	}

	@Test
	void containersAreThePodsOwnInTheOrderItDeclaresThem() {
		seedPod("app", "sidecar", "proxy");

		assertThat(CoreStack.dataSource(this.client).containers(CoreStack.CLUSTER, "web", "nginx"))
			.containsExactly("app", "sidecar", "proxy");
	}

	/**
	 * A pod that is not there is a refusal the pane turns into a sentence, not an empty
	 * container list — an empty list would let the pane open on a pod that does not exist
	 * and follow nothing.
	 */
	@Test
	void aPodThatIsNotThereIsRefusedRatherThanAnsweredWithNoContainers() {
		CoreClusterDataSource source = CoreStack.dataSource(this.client);

		assertThatThrownBy(() -> source.containers(CoreStack.CLUSTER, "web", "gone"))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("No such pod: web/gone");
	}

	/**
	 * <b>Done-when 3, at the port.</b> The API server refuses a previous-run read for a
	 * container that has not restarted, and {@code LogService.previous} answers null for
	 * it; the port turns that into words, so no caller can render it as an empty
	 * document.
	 */
	@Test
	void noPreviousRunComesBackAsWordsRatherThanAnEmptyLog() {
		// The API server's own answer for a container that has never restarted, written
		// out because the CRUD mock does not serve /log at all and answers an empty 200 —
		// which is a DIFFERENT real state (see the test below), so letting the mock's
		// default stand in for the refusal would have tested neither.
		this.server.expect()
			.get()
			.withPath(PREVIOUS_PATH)
			.andReturn(400,
					"{\"kind\":\"Status\",\"status\":\"Failure\","
							+ "\"message\":\"previous terminated container \\\"app\\\" not found\"}")
			.always();

		PreviousLog previous = CoreStack.dataSource(this.client)
			.previousLog(new PodTarget(CoreStack.CLUSTER, "web", "nginx", "app"), 100);

		assertThat(previous.available()).isFalse();
		assertThat(previous.reason()).contains("container 'app'").contains("has not restarted");
		assertThat(previous.text()).isEmpty();
	}

	/**
	 * <b>The third state.</b> A container that <em>did</em> restart and wrote nothing
	 * answers an empty 200 — a different fact from "it has not restarted", and precisely
	 * the two a crashloop reader is choosing between. Both are sentences; neither is a
	 * blank pane.
	 */
	@Test
	void aPreviousRunThatWroteNothingSaysThatRatherThanLookingLikeNoPreviousRun() {
		this.server.expect().get().withPath(PREVIOUS_PATH).andReturn(200, "").always();

		PreviousLog previous = CoreStack.dataSource(this.client)
			.previousLog(new PodTarget(CoreStack.CLUSTER, "web", "nginx", "app"), 100);

		assertThat(previous.available()).isFalse();
		assertThat(previous.reason()).isEqualTo("The previous run of container 'app' wrote nothing to its log.");
	}

	@Test
	void aPreviousRunThatExistsComesBackAsItsLog() {
		this.server.expect().get().withPath(PREVIOUS_PATH).andReturn(200, "panic: boom\nexit status 2").always();

		PreviousLog previous = CoreStack.dataSource(this.client)
			.previousLog(new PodTarget(CoreStack.CLUSTER, "web", "nginx", "app"), 100);

		assertThat(previous.available()).isTrue();
		assertThat(previous.text()).isEqualTo("panic: boom\nexit status 2");
	}

	/**
	 * A blank container is the pod's default one, which is what {@code kubectl} does —
	 * and the sentence has to name it in words rather than quoting an empty string.
	 */
	@Test
	void aBlankContainerIsThePodsDefaultOneAndTheRefusalSaysSo() {
		this.server.expect()
			.get()
			.withPath("/api/v1/namespaces/web/pods/nginx/log?pretty=false&previous=true&tailLines=100")
			.andReturn(400, "{\"kind\":\"Status\",\"status\":\"Failure\",\"message\":\"not found\"}")
			.always();

		PreviousLog previous = CoreStack.dataSource(this.client)
			.previousLog(new PodTarget(CoreStack.CLUSTER, "web", "nginx", ""), 100);

		assertThat(previous.reason()).contains("the pod's default container");
	}

	/**
	 * <b>The two follows are asserted on the OUTGOING QUERY STRING</b>, which is the
	 * module's standing rule for a flag: the mock serves a stream either way, so a test
	 * that read the stream back would pass whether or not {@code timestamps} was ever
	 * sent. {@code tailLines=0} is the other half and is just as load-bearing — without
	 * it fabric8 replays the pod's <em>entire</em> log on connect, which on a chatty pod
	 * is tens of thousands of lines into a 5 000-line buffer before the first frame.
	 */
	@Test
	void theTimestampedFollowAsksForTimestampsAndForNoHistory() throws InterruptedException {
		followAndAssertPath(true, "timestamps=true");
	}

	/** And the plain follow does not ask for them. */
	@Test
	void thePlainFollowAsksForNoTimestamps() throws InterruptedException {
		followAndAssertPath(false, null);
	}

	private void followAndAssertPath(boolean timestamps, String expectedFlag) throws InterruptedException {
		CoreClusterDataSource source = CoreStack.dataSource(this.client);
		PodTarget target = new PodTarget(CoreStack.CLUSTER, "web", "nginx", "app");

		try (LogStream stream = (timestamps) ? source.logsWithTimestamps(target) : source.logs(target)) {
			assertThat(stream).isNotNull();
		}

		String path = this.server.getLastRequest().getPath();
		assertThat(path).contains("/api/v1/namespaces/web/pods/nginx/log")
			.contains("container=app")
			.contains("follow=true")
			.contains("tailLines=0");
		if (expectedFlag != null) {
			assertThat(path).contains(expectedFlag);
		}
		else {
			assertThat(path).doesNotContain("timestamps");
		}
	}

}
