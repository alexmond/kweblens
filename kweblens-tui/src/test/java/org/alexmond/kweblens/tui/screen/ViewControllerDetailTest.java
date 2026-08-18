package org.alexmond.kweblens.tui.screen;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import org.alexmond.kweblens.event.EventSummary;
import org.alexmond.kweblens.resource.Relation;
import org.alexmond.kweblens.resource.WellKnownKinds;
import org.alexmond.kweblens.tui.data.ObjectDetail;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>The gate for the detail pane's keys</b> (GH#368): what {@code d} opens, what
 * {@code /} searches, and the order {@code esc} undoes things in.
 *
 * <p>
 * No terminal and no cluster: a key goes in as a {@link KeyStroke} and what comes out is
 * a document, a cursor and a footer sentence.
 */
class ViewControllerDetailTest {

	private static final ObjectDetail SEEDED = ObjectDetail.of("apiVersion: v1\nkind: Pod\nmetadata:\n  name: web\n",
			Map.of("selectedPods", Relation.of(List.of())),
			List.of(new EventSummary("Warning", "BackOff", "Pod/web", "ns", "Back-off restarting", "2m")));

	private final FakeNavigation navigation = new FakeNavigation().withDetail(SEEDED);

	private final ResourceModel model = new ResourceModel();

	private final ViewController controller = new ViewController(this.navigation, this.model,
			View.of(WellKnownKinds.PODS, "kube-system"), () -> 10);

	private void seedRow(String name, String state) {
		this.model.upsert(List.of(new ResourceRow("kube-system/" + name, "kube-system", name, state, "4h")));
	}

	private void press(char key) {
		this.controller.key(KeyStroke.of(key));
	}

	private void search(String text) {
		press('/');
		text.chars().forEach((c) -> this.controller.key(KeyStroke.of((char) c)));
		this.controller.key(KeyStroke.key(KeyStroke.Kind.ENTER));
	}

	@Test
	void dOpensThePaneOnTheSelectedObject() {
		seedRow("web", "Running");

		press('d');

		assertThat(this.controller.paneOpen()).isTrue();
		assertThat(this.navigation.detailsRead()).containsExactly("kube-system/web");
		assertThat(this.controller.detail().lines()).extracting((line) -> line.text())
			.contains("kind: Pod")
			.anySatisfy((line) -> assertThat(line).contains("Selected Pods"))
			.anySatisfy((line) -> assertThat(line).contains("BackOff"));
	}

	/**
	 * Done-when 6: the pane's headline is the verdict the <em>list</em> computed. It is
	 * read off the row rather than asked for again, because a per-object verdict opens a
	 * status context per object and the list work exists not to do that.
	 */
	@Test
	void theHeadlineIsTheVerdictTheListAlreadyComputed() {
		seedRow("web", "CrashLoopBackOff");

		press('d');

		assertThat(this.controller.detail().subject()).as("the headline is the frame title's, so it stays on screen")
			.startsWith("CrashLoopBackOff  ·  Pod  ·  ");
	}

	@Test
	void aRowThatNothingJudgedSaysThatRatherThanReadingAsOk() {
		seedRow("web", null);

		press('d');

		assertThat(this.controller.detail().subject()).startsWith("— no verdict");
	}

	@Test
	void withNothingSelectedThereIsNothingToOpen() {
		press('d');

		assertThat(this.controller.paneOpen()).isFalse();
		assertThat(this.controller.message()).isEqualTo("Nothing selected.");
	}

	/**
	 * A read the cluster refused is a sentence in the footer and no pane — an empty pane
	 * would claim this object has no relations and no events.
	 */
	@Test
	void aDetailTheClusterWouldNotServeIsASentence_notAPane() {
		seedRow("web", "Running");
		this.navigation.withDetail(ObjectDetail.failed("Could not read Pod web: forbidden"));

		press('d');

		assertThat(this.controller.paneOpen()).isFalse();
		assertThat(this.controller.message()).isEqualTo("Could not read Pod web: forbidden");
	}

	@Test
	void slashSearchesThePaneAndNAndCapitalNWalkTheMatches() {
		seedRow("web", "Running");
		press('d');

		search("name");

		assertThat(this.controller.detail().matchCount()).isEqualTo(1);
		assertThat(this.controller.detail().searchStatus()).isEqualTo("/name  match 1 of 1");
	}

	@Test
	void aSearchThatMatchesNothingSaysSoRatherThanLeavingTheScreenUnchanged() {
		seedRow("web", "Running");
		press('d');

		search("no-such-text");

		assertThat(this.controller.detail().searchStatus()).isEqualTo("/no-such-text  no match");
	}

	@Test
	void nWithNoSearchYetSaysWhatToPressFirst() {
		seedRow("web", "Running");
		press('d');

		press('n');

		assertThat(this.controller.message()).isEqualTo("Press / to search this pane first.");
	}

	/**
	 * <b>{@code esc} clears the search before it closes the pane</b> — the same order
	 * {@link ViewStack#back()} keeps for a filter and a level, and for the same reason:
	 * one press must undo one thing.
	 */
	@Test
	void escClearsTheSearchBeforeItClosesThePane() {
		seedRow("web", "Running");
		press('d');
		search("kind");

		this.controller.key(KeyStroke.key(KeyStroke.Kind.ESCAPE));

		assertThat(this.controller.paneOpen()).as("the first esc took the search, not the pane").isTrue();
		assertThat(this.controller.detail().query()).isEmpty();

		this.controller.key(KeyStroke.key(KeyStroke.Kind.ESCAPE));

		assertThat(this.controller.paneOpen()).isFalse();
	}

	/**
	 * Closing the pane returns to the list the operator was on. It is not a view-stack
	 * level: the pane never changed which kind is being watched, so popping one would
	 * take them somewhere they did not go.
	 */
	@Test
	void closingThePaneLeavesTheListWhereItWas() {
		seedRow("web", "Running");
		press('d');

		press('q');

		assertThat(this.controller.paneOpen()).isFalse();
		assertThat(this.controller.current().descriptor()).isEqualTo(WellKnownKinds.PODS);
		assertThat(this.controller.depth()).isEqualTo(1);
	}

	/**
	 * The list's keys do not reach through the pane. {@code :} in the pane is not a
	 * command line, because the pane's own table does not bind it — which is the whole
	 * reason there are two tables.
	 */
	@Test
	void theListsKeysDoNotActThroughThePane() {
		seedRow("web", "Running");
		seedRow("other", "Running");
		press('d');

		press(':');

		assertThat(this.controller.prompt().open()).isFalse();
	}

	/**
	 * The two doors are connected. Enter stays the drill-down (#366) and declines in
	 * words where no query can express the relationship — for most kinds, which is
	 * exactly when the pane is what the operator wanted.
	 */
	@Test
	void aDrillDownThatDeclinesPointsAtThePane() {
		seedRow("web", "Running");
		this.navigation.withObject(null);

		this.controller.key(KeyStroke.key(KeyStroke.Kind.ENTER));

		assertThat(this.controller.message()).endsWith("Press d for this object's detail.");
	}

	@Test
	void movingInThePaneMovesThePanesCursorAndNotTheTables() {
		seedRow("web", "Running");
		seedRow("other", "Running");
		press('d');
		int before = this.model.selectedIndex();

		press('j');
		press('j');

		assertThat(this.controller.detail().selectedIndex()).isEqualTo(2);
		assertThat(this.model.selectedIndex()).as("the table under the pane did not move").isEqualTo(before);
	}

}
