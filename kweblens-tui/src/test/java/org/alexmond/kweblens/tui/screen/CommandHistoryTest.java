package org.alexmond.kweblens.tui.screen;

import org.junit.jupiter.api.Test;

import org.alexmond.kweblens.resource.WellKnownKinds;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>History is not the stack.</b> {@code [}, {@code ]} and {@code -} walk what you asked
 * for; {@code esc} walks where you are, and neither moves the other.
 *
 * <p>
 * They are easy to collapse into one thing because both feel like "back", and the result
 * is a {@code [} that pops a view — which looks right until the two get out of step and
 * then cannot be reasoned about at all. So the last test here drives both at once and
 * asserts each is untouched by the other.
 */
class CommandHistoryTest {

	private final CommandHistory history = new CommandHistory();

	@Test
	void previousAndNextWalkBackwardsAndForwardsThroughWhatWasRun() {
		this.history.record("pods");
		this.history.record("deploy");
		this.history.record("svc");

		assertThat(this.history.previous()).contains("svc");
		assertThat(this.history.previous()).contains("deploy");
		assertThat(this.history.previous()).contains("pods");
		assertThat(this.history.previous()).as("nothing before the first").isEmpty();

		assertThat(this.history.next()).contains("deploy");
		assertThat(this.history.next()).contains("svc");
		assertThat(this.history.next()).as("walking off the end lands on a fresh prompt").isEmpty();
	}

	@Test
	void runningACommandPutsTheCursorBackAtTheEndTheWayAShellDoes() {
		this.history.record("pods");
		this.history.record("deploy");
		this.history.previous();
		this.history.previous();

		this.history.record("svc");

		assertThat(this.history.cursor()).isEqualTo(this.history.size());
		assertThat(this.history.previous()).contains("svc");
	}

	@Test
	void theSameCommandTwiceIsOneThingToWalkBackThrough() {
		this.history.record("pods");
		this.history.record("pods");

		assertThat(this.history.size()).isOne();
	}

	@Test
	void lastTogglesBetweenTheTwoMostRecentRatherThanWalkingBackwardsForever() {
		this.history.record("pods");
		this.history.record("deploy");

		assertThat(this.history.last()).contains("pods");
		assertThat(this.history.last()).as("and back again — that is what a last-view key does").contains("deploy");
		assertThat(this.history.last()).contains("pods");
	}

	@Test
	void lastHasNowhereToGoWithOnlyOneCommandBehindIt() {
		assertThat(this.history.last()).isEmpty();
		this.history.record("pods");
		assertThat(this.history.last()).isEmpty();
	}

	@Test
	void olderCommandsFallOffTheFrontRatherThanGrowingWithoutBound() {
		for (int i = 0; i < CommandHistory.LIMIT + 10; i++) {
			this.history.record("cmd-" + i);
		}

		assertThat(this.history.size()).isEqualTo(CommandHistory.LIMIT);
		assertThat(this.history.entries()).first().isEqualTo("cmd-10");
	}

	@Test
	void walkingHistoryMovesNoLevelAndPushingALevelRecordsNoCommand() {
		FakeNavigation navigation = new FakeNavigation();
		ResourceModel model = new ResourceModel();
		ViewController controller = new ViewController(navigation, model, View.of(WellKnownKinds.PODS, null), () -> 10);

		// Two commands: two levels pushed, two entries recorded.
		run(controller, "ns");
		run(controller, "deploy");
		assertThat(controller.depth()).isEqualTo(3);
		assertThat(controller.history().entries()).containsExactly("ns", "deploy");

		// A drill-down pushes a level and records nothing.
		navigation
			.withObject(new io.fabric8.kubernetes.api.model.GenericKubernetesResourceBuilder().withApiVersion("apps/v1")
				.withKind("Deployment")
				.withNewMetadata()
				.withNamespace("kube-system")
				.withName("coredns")
				.endMetadata()
				.addToAdditionalProperties("spec",
						java.util.Map.of("selector", java.util.Map.of("matchLabels", java.util.Map.of("app", "dns"))))
				.build());
		model.replaceAll(
				java.util.List.of(new ResourceRow("kube-system/coredns", "kube-system", "coredns", null, "1d")));
		controller.key(KeyStroke.key(KeyStroke.Kind.ENTER));
		assertThat(controller.depth()).isEqualTo(4);
		assertThat(controller.history().entries()).as("drilling in is not a command").containsExactly("ns", "deploy");

		// And walking history pops nothing: it pushes, like every other way of choosing a
		// view, and the history cursor is the only thing that moved backwards.
		int depth = controller.depth();
		controller.key(KeyStroke.of('['));
		assertThat(controller.history().cursor()).isEqualTo(1);
		assertThat(controller.depth()).as("[ is temporal; it does not pop").isEqualTo(depth + 1);
		assertThat(controller.current().crumb()).isEqualTo("deployments");

		controller.key(KeyStroke.of('['));
		assertThat(controller.current().crumb()).isEqualTo("namespaces");
		assertThat(controller.history().cursor()).isZero();
	}

	private static void run(ViewController controller, String line) {
		controller.key(KeyStroke.of(':'));
		line.chars().forEach((c) -> controller.key(KeyStroke.of((char) c)));
		controller.key(KeyStroke.key(KeyStroke.Kind.ENTER));
	}

}
