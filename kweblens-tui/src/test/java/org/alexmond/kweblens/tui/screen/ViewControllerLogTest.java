package org.alexmond.kweblens.tui.screen;

import java.util.List;

import org.junit.jupiter.api.Test;

import org.alexmond.kweblens.resource.WellKnownKinds;
import org.alexmond.kweblens.tui.log.LogRequest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>The gate for the log pane's keys</b> (GH#369): what {@code l} and {@code p} open,
 * what {@code c} and {@code t} re-open, and — the one that matters — that <b>every one of
 * them releases the follow it replaces</b>.
 *
 * <p>
 * No terminal and no cluster. A key goes in as a {@link KeyStroke} and what comes out is
 * a document, a request the session was asked for, and a count of releases.
 */
class ViewControllerLogTest {

	private final FakeNavigation navigation = new FakeNavigation();

	private final ResourceModel model = new ResourceModel();

	private ViewController controller = controllerOn(WellKnownKinds.PODS);

	private ViewController controllerOn(org.alexmond.kweblens.resource.ResourceDescriptor kind) {
		return new ViewController(this.navigation, this.model, View.of(kind, "kube-system"), () -> 10);
	}

	private void seedRow(String name) {
		this.model.upsert(List.of(new ResourceRow("kube-system/" + name, "kube-system", name, "Running", "4h")));
	}

	private void press(char key) {
		this.controller.key(KeyStroke.of(key));
	}

	@Test
	void lOpensTheLogPaneOnTheSelectedPod() {
		seedRow("web-0");

		press('l');

		assertThat(this.controller.logsOpen()).isTrue();
		assertThat(this.navigation.logsOpened())
			.containsExactly(new LogRequest("kube-system", "web-0", "", false, false));
		assertThat(this.controller.logs().title()).startsWith("logs").contains("kube-system/web-0");
	}

	/**
	 * k9s binds {@code l} and {@code p}, and they are different readings rather than a
	 * mode: one is a live follow of a running process, the other a snapshot of one that
	 * has already exited.
	 */
	@Test
	void pOpensThePreviousRunAndTheTitleSaysSo() {
		seedRow("web-0");

		press('p');

		assertThat(this.controller.logs().isPrevious()).isTrue();
		assertThat(this.controller.logs().title()).startsWith("previous run (snapshot)");
		assertThat(this.navigation.logsOpened()).singleElement().extracting(LogRequest::previous).isEqualTo(true);
	}

	/**
	 * <b>The key assertion of GH#369, at the controller.</b> Every key that changes what
	 * is being read is a re-open, and every re-open owes the previous follow a release —
	 * the session performs it, and this counts that it was asked for. A pane that
	 * switched container without releasing would look identical on screen and leak one
	 * connection to the API server per press.
	 */
	@Test
	void everyKeyThatChangesTheReadingReleasesTheFollowItReplaces() {
		this.navigation.withContainers("app", "sidecar");
		seedRow("web-0");

		press('l');
		press('c');
		press('t');
		press('p');
		this.controller.key(KeyStroke.key(KeyStroke.Kind.ESCAPE));

		assertThat(this.controller.logsOpen()).isFalse();
		assertThat(this.navigation.logsOpened()).as("open, container, timestamps, previous").hasSize(4);
		assertThat(this.navigation.logsClosed())
			.as("one release per re-open, plus the one esc owes — a missing one here is a leaked connection")
			.isEqualTo(5);
	}

	@Test
	void cWalksTheContainersAndWraps() {
		this.navigation.withContainers("app", "sidecar", "proxy");
		seedRow("web-0");

		press('l');
		press('c');
		assertThat(this.controller.logs().container()).isEqualTo("sidecar");
		press('c');
		assertThat(this.controller.logs().container()).isEqualTo("proxy");
		press('c');
		assertThat(this.controller.logs().container()).isEqualTo("app");
	}

	/**
	 * A single-container pod answers the question rather than re-opening the same follow
	 * — which would be a connection churn and a flicker for no change on screen.
	 */
	@Test
	void cOnASingleContainerPodSaysSoAndOpensNothing() {
		this.navigation.withContainers("app");
		seedRow("web-0");
		press('l');

		press('c');

		assertThat(this.navigation.logsOpened()).as("no second open").hasSize(1);
		assertThat(this.controller.message()).contains("only one container (app)");
	}

	@Test
	void tTogglesTimestampsByReopeningTheStream() {
		seedRow("web-0");
		press('l');

		press('t');

		assertThat(this.controller.logs().hasTimestamps()).isTrue();
		assertThat(this.navigation.logsOpened()).last().extracting(LogRequest::timestamps).isEqualTo(true);
	}

	/**
	 * The API server stamps the lines, and it does not stamp a previous run — so the pane
	 * says that rather than re-opening a snapshot that would come back identical.
	 */
	@Test
	void tOnAPreviousRunExplainsWhyThereIsNothingToToggle() {
		seedRow("web-0");
		press('p');

		press('t');

		assertThat(this.navigation.logsOpened()).hasSize(1);
		assertThat(this.controller.message()).contains("does not stamp").contains("press p for the live log");
	}

	/** {@code p} inside the pane goes back and forth rather than only one way. */
	@Test
	void pInsideThePaneTogglesBetweenTheTwoRuns() {
		seedRow("web-0");
		press('l');

		press('p');
		assertThat(this.controller.logs().isPrevious()).isTrue();
		press('p');
		assertThat(this.controller.logs().isPrevious()).isFalse();
	}

	/**
	 * <b>A refusal is a sentence and the pane keeps what it had</b> (GH#434). The session
	 * opens the new follow before releasing the old one for exactly this: a container the
	 * cluster refuses must not cost the reader the buffer they were already reading.
	 */
	@Test
	void aRefusedReopenLeavesThePaneOnWhatItWasShowing() {
		this.navigation.withContainers("app", "sidecar");
		seedRow("web-0");
		press('l');
		this.navigation.refusingLogs("forbidden: pods/log is not readable");

		press('c');

		assertThat(this.controller.logsOpen()).as("still showing what it had").isTrue();
		assertThat(this.controller.logs().container()).isEqualTo("app");
		assertThat(this.controller.message()).isEqualTo("forbidden: pods/log is not readable");
	}

	@Test
	void aRefusedFirstOpenDoesNotOpenAPaneAtAll() {
		seedRow("web-0");
		this.navigation.refusingLogs("No such pod: kube-system/web-0");

		press('l');

		assertThat(this.controller.logsOpen()).isFalse();
		assertThat(this.controller.message()).isEqualTo("No such pod: kube-system/web-0");
	}

	/**
	 * Logs are a container's. From a Deployment the pane says where to go rather than
	 * asking the cluster for the containers of a workload and reporting whatever that
	 * failed with.
	 */
	@Test
	void lOnAKindWithNoContainersSaysWhereToGoInstead() {
		this.controller = controllerOn(FakeNavigation.DEPLOYMENTS);
		seedRow("web");

		press('l');

		assertThat(this.controller.logsOpen()).isFalse();
		assertThat(this.navigation.logsOpened()).isEmpty();
		assertThat(this.controller.message()).contains("Deployment has no containers")
			.contains("Press ↵ on a workload to reach its pods");
	}

	@Test
	void lWithNothingSelectedSaysSo() {
		press('l');

		assertThat(this.controller.logsOpen()).isFalse();
		assertThat(this.controller.message()).isEqualTo("Nothing selected.");
	}

	/**
	 * The log pane owns the keyboard while it is up: {@code :} does not open a command
	 * line over a table nobody is looking at, and {@code d} does not open the detail pane
	 * behind it. Its own table is the enforcement — see {@code KeyMapTest}.
	 */
	@Test
	void theLogPaneOwnsTheKeyboardWhileItIsUp() {
		seedRow("web-0");
		press('l');

		press(':');
		press('d');

		assertThat(this.controller.prompt().open()).isFalse();
		assertThat(this.controller.paneOpen()).as("the detail pane did not open behind the log").isFalse();
		assertThat(this.controller.logsOpen()).isTrue();
	}

	/**
	 * The screen's teardown releases the follow even with the pane still up — the same
	 * obligation {@code esc} discharges, from the other end, and the path that would
	 * otherwise leave a connection open for the life of the process.
	 */
	@Test
	void releasingFromTeardownClosesAPaneThatWasNeverEscaped() {
		seedRow("web-0");
		press('l');

		this.controller.releaseLogs();

		assertThat(this.controller.logsOpen()).isFalse();
		assertThat(this.navigation.logsClosed()).isEqualTo(2);
	}

	@Test
	void theCursorKeysMoveThroughTheLogAndGResumesFollowing() {
		seedRow("web-0");
		press('l');
		for (int i = 0; i < 20; i++) {
			this.controller.logs().append("line-" + i);
		}
		this.controller.logs().flush();

		press('k');
		assertThat(this.controller.logs().following()).isFalse();
		assertThat(this.controller.logs().selectedIndex()).isEqualTo(18);

		press('G');
		assertThat(this.controller.logs().following()).isTrue();
		assertThat(this.controller.logs().selectedIndex()).isEqualTo(19);
	}

}
