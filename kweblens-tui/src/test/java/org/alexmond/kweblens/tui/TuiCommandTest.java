package org.alexmond.kweblens.tui;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import org.alexmond.kweblens.cluster.ClusterRegistry;
import org.alexmond.kweblens.tui.data.ClusterDataSource;
import org.alexmond.kweblens.tui.data.CoreStack;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The argv seam end to end, against the in-JVM API server.
 *
 * <p>
 * {@code kweblens.tui.load-kubeconfig} is off, so the only cluster is the one this test
 * registers: nothing here reads the operator's kubeconfig or reaches a real cluster.
 */
@EnableKubernetesMockClient(crud = true)
class TuiCommandTest {

	KubernetesClient client;

	private Run run(String... args) {
		TuiProperties properties = new TuiProperties();
		properties.setLoadKubeconfig(false);
		ClusterRegistry registry = CoreStack.registry(this.client);
		ClusterDataSource source = CoreStack.dataSource(registry);
		TuiClusterBootstrap bootstrap = new TuiClusterBootstrap(properties, registry);
		TuiCommand command = new TuiCommand(bootstrap, source, new TuiListing(source), properties);

		ByteArrayOutputStream captured = new ByteArrayOutputStream();
		PrintStream original = System.out;
		int code;
		try {
			System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
			code = new CommandLine(command).execute(args);
		}
		finally {
			System.setOut(original);
		}
		return new Run(code, captured.toString(StandardCharsets.UTF_8));
	}

	private void seedNamespace(String name) {
		this.client.namespaces()
			.resource(new NamespaceBuilder().withNewMetadata().withName(name).endMetadata().build())
			.create();
	}

	@Test
	void listsAKindAndExitsZero() {
		seedNamespace("kweblens-demo");

		Run run = run("--context", CoreStack.CLUSTER, "--kind", "namespaces");

		assertThat(run.code()).isZero();
		assertThat(run.output()).contains("kweblens-tui [R]").contains("NAMESPACE").contains("kweblens-demo");
	}

	@Test
	void contextsListsWhatCanBeOpenedAndNothingElse() {
		Run run = run("--contexts");

		assertThat(run.code()).isZero();
		assertThat(run.output().trim()).isEqualTo(CoreStack.CLUSTER);
	}

	@Test
	void anUnknownContextIsRefusedWithTheOnesThatExist() {
		Run run = run("--context", "not-a-context");

		assertThat(run.code()).isEqualTo(2);
		assertThat(run.output()).contains("Unknown context").contains(CoreStack.CLUSTER);
	}

	@Test
	void anUnknownKindIsRefusedWithTheOnesThisBuildKnows() {
		Run run = run("--context", CoreStack.CLUSTER, "--kind", "widgets");

		assertThat(run.code()).isEqualTo(2);
		assertThat(run.output()).contains("Unknown kind").contains("pods");
	}

	@Test
	void aSingleRegisteredClusterNeedsNoContextFlag() {
		seedNamespace("only-one");

		Run run = run("--kind", "namespaces");

		assertThat(run.code()).isZero();
		assertThat(run.output()).contains("only-one");
	}

	/** What one invocation produced: the exit code picocli returned, and the stdout. */
	private record Run(int code, String output) {
	}

}
