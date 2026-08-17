package org.alexmond.kweblens.web.mcp;

import io.fabric8.kubernetes.api.model.ContainerStateBuilder;
import io.fabric8.kubernetes.api.model.ContainerStatusBuilder;
import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import org.alexmond.kweblens.cluster.ClusterRegistry;
import org.alexmond.kweblens.web.ai.DiagnoseResult;
import org.alexmond.kweblens.web.ai.Finding;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The deterministic findings, reached the way an assistant reaches them (#383).
 *
 * <p>
 * Until this tool existed an agent attached to kweblens could ask what the cluster
 * <i>permits</i> — {@code checkSecurity} — but not what is <i>wrong with it</i>, which is
 * the more obvious question and the one the deterministic checks were written for. Every
 * other dimension had a tool; the finding list, which is the answer that joins them, had
 * none.
 *
 * <p>
 * The model is switched off explicitly, because the claim is that the findings are
 * computed rather than written: {@code aiEnriched} stays false and the crashloop is still
 * named, with the exit code that says what killed it.
 */
@SpringBootTest(properties = { "kweblens.load-kubeconfig=false", "kweblens.ai.enabled=false" })
@EnableKubernetesMockClient(crud = true)
class DiagnoseToolTest {

	private static final String CLUSTER = "mcp-diagnose";

	KubernetesClient client;

	@Autowired
	private ClusterRegistry registry;

	@Autowired
	private HealthTools tools;

	@BeforeEach
	void seed() {
		this.registry.register(CLUSTER, "Diagnose tool", this.client);
		this.client.namespaces()
			.resource(new NamespaceBuilder().withNewMetadata().withName("web").endMetadata().build())
			.create();
		this.client.pods()
			.inNamespace("web")
			.resource(new PodBuilder().withNewMetadata()
				.withName("api-1")
				.withNamespace("web")
				.endMetadata()
				.withNewSpec()
				.addNewContainer()
				.withName("app")
				.endContainer()
				.endSpec()
				.withNewStatus()
				.withPhase("Running")
				.withContainerStatuses(new ContainerStatusBuilder().withName("app")
					.withState(new ContainerStateBuilder().withNewWaiting()
						.withReason("CrashLoopBackOff")
						.endWaiting()
						.build())
					.withNewLastState()
					.withNewTerminated()
					.withExitCode(1)
					.withReason("Error")
					.endTerminated()
					.endLastState()
					.build())
				.endStatus()
				.build())
			.create();
	}

	@AfterEach
	void unregister() {
		this.registry.unregister(CLUSTER);
	}

	@Test
	void namesTheBrokenContainerWithTheEvidenceAndTheFix() {
		DiagnoseResult result = this.tools.diagnose(CLUSTER, "web");

		assertThat(result.aiEnriched()).as("a read never buys an analysis").isFalse();
		assertThat(result.summary()).isNull();
		assertThat(result.findings()).isNotEmpty();
		Finding crashloop = result.findings()
			.stream()
			.filter((f) -> "Pod/api-1 container app".equals(f.object()))
			.findFirst()
			.orElseThrow();
		assertThat(crashloop.severity()).isEqualTo("critical");
		assertThat(crashloop.title()).isEqualTo("CrashLoopBackOff");
		// The exit code is the diagnosis: 1 is the application failing, 137 is the
		// kernel.
		assertThat(crashloop.detail()).contains("last exit code 1");
		assertThat(crashloop.suggestedFix()).contains("PREVIOUS");
		// Every finding is deterministic. An "ai"-sourced one could only have come from a
		// model, which this path may not reach.
		assertThat(result.findings()).allMatch((f) -> "validator".equals(f.source()));
	}

	/**
	 * The cluster-wide scope is the default an assistant gets when it omits the
	 * namespace, so it has to work rather than throw on a null.
	 */
	@Test
	void answersTheWholeClusterWhenNoNamespaceIsGiven() {
		DiagnoseResult result = this.tools.diagnose(CLUSTER, null);

		assertThat(result.findings()).anyMatch((f) -> "Pod/api-1 container app".equals(f.object()));
	}

}
