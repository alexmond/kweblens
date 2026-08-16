package org.alexmond.kweblens.tui;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import org.alexmond.kweblens.cluster.ClusterRegistry;
import org.alexmond.kweblens.tui.data.ClusterDataSource;
import org.alexmond.kweblens.tui.data.CoreStack;
import org.alexmond.kweblens.tui.data.ResourceQuery;
import org.alexmond.kweblens.tui.kind.KindCatalog;
import org.alexmond.kweblens.tui.render.Screen;
import org.alexmond.kweblens.tui.screen.TickRate;

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

	KubernetesMockServer server;

	/** What the screen was asked to draw, if it was asked at all. */
	private final AtomicReference<ResourceQuery> drawn = new AtomicReference<>();

	private final AtomicReference<TickRate> rate = new AtomicReference<>();

	private Run run(String... args) {
		CoreStack.stubDiscovery(this.server);
		TuiProperties properties = new TuiProperties();
		properties.setLoadKubeconfig(false);
		ClusterRegistry registry = CoreStack.registry(this.client);
		ClusterDataSource source = CoreStack.dataSource(registry);
		TuiClusterBootstrap bootstrap = new TuiClusterBootstrap(properties, registry);
		Screen screen = (query, chunkSize, tick, out) -> {
			this.drawn.set(query);
			this.rate.set(tick);
			return 0;
		};
		TuiCommand command = new TuiCommand(bootstrap, source, new TuiListing(source), screen, new KindCatalog(source),
				properties);

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
	void onceListsAKindAndExitsZero() {
		seedNamespace("kweblens-demo");

		Run run = run("--once", "--context", CoreStack.CLUSTER, "--kind", "namespaces");

		assertThat(run.code()).isZero();
		assertThat(run.output()).contains("kweblens-tui [R]").contains("NAMESPACE").contains("kweblens-demo");
		assertThat(this.drawn.get()).as("--once must not open a screen").isNull();
	}

	@Test
	void withoutOnceItOpensTheScreenOnWhatTheFlagsResolvedTo() {
		Run run = run("--context", CoreStack.CLUSTER, "--kind", "namespaces", "--namespace", "kube-system");

		assertThat(run.code()).isZero();
		assertThat(run.output()).as("the screen owns the terminal, so nothing is printed to it").isEmpty();
		assertThat(this.drawn.get()).isNotNull()
			.satisfies((query) -> assertThat(query.clusterId()).isEqualTo(CoreStack.CLUSTER))
			.satisfies((query) -> assertThat(query.kind()).isEqualTo("Namespace"))
			.satisfies((query) -> assertThat(query.namespace()).isEqualTo("kube-system"));
		assertThat(this.rate.get()).isEqualTo(TickRate.defaults());
	}

	@Test
	void aTickBelowTheFloorReachesTheScreenAlreadyClampedAndStillCarriesWhatWasAsked() {
		Run run = run("--context", CoreStack.CLUSTER, "--tick", "1");

		assertThat(run.code()).isZero();
		assertThat(this.rate.get().clamped()).isTrue();
		assertThat(this.rate.get().millis()).isEqualTo(TickRate.FLOOR.toMillis());
		assertThat(this.rate.get().notice()).contains("1ms");
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
	void anUnknownKindIsRefusedAndSaysHowManyTheClusterDoesServe() {
		Run run = run("--context", CoreStack.CLUSTER, "--kind", "widgets");

		assertThat(run.code()).isEqualTo(2);
		assertThat(run.output()).contains("Unknown kind 'widgets'").contains("kinds").contains("--aliases");
		assertThat(this.drawn.get()).as("a refusal must not have opened a screen first").isNull();
	}

	@Test
	void aKindIsResolvedByItsServerDeclaredShortName() {
		seedNamespace("short-name-demo");

		Run run = run("--once", "--kind", "ns");

		assertThat(run.code()).isZero();
		assertThat(run.output()).contains("short-name-demo");
	}

	@Test
	void aliasesListEveryNameTheCommandLineAnswersToIncludingACrdTheCatalogHasNever() {
		Run run = run("--aliases");

		assertThat(run.code()).isZero();
		assertThat(run.output().lines().toList()).contains("po", "pods", "pod", "v1/pods")
			.as("a CRD kind no NavCatalog lists, reachable because discovery found it")
			.contains("ingressroutes", "ingressroute", "ingressroutes.traefik.io", "traefik.io/v1alpha1/ingressroutes");
	}

	@Test
	void aSingleRegisteredClusterNeedsNoContextFlag() {
		seedNamespace("only-one");

		Run run = run("--once", "--kind", "namespaces");

		assertThat(run.code()).isZero();
		assertThat(run.output()).contains("only-one");
	}

	/** What one invocation produced: the exit code picocli returned, and the stdout. */
	private record Run(int code, String output) {
	}

}
