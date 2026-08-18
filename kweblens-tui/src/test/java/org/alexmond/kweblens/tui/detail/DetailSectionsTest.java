package org.alexmond.kweblens.tui.detail;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.client.utils.Serialization;
import org.junit.jupiter.api.Test;

import org.alexmond.kweblens.event.EventSummary;
import org.alexmond.kweblens.resource.Relation;
import org.alexmond.kweblens.tui.data.ObjectDetail;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>The gate for "three states, three different statements".</b>
 *
 * <p>
 * A {@link Relation} that was truncated, one that failed and one that RBAC refused are
 * three claims about the cluster, and the failure this test exists for is the one where
 * two of them collapse into the fourth — an empty section, which says "there are none".
 * That is not a cosmetic difference: a reader told a Service has no endpoints goes
 * looking for a broken selector rather than for a permissions problem.
 *
 * <p>
 * The assertions are on the exact words, because the words are the feature.
 */
class DetailSectionsTest {

	private static Pod pod(String name) {
		return new PodBuilder().withNewMetadata().withName(name).withNamespace("web").endMetadata().build();
	}

	private static ObjectDetail detail(Map<String, Relation> relations) {
		return ObjectDetail.of("apiVersion: v1\nkind: Pod\n", relations, List.of());
	}

	private static List<String> text(List<DetailLine> lines) {
		return lines.stream().map(DetailLine::text).toList();
	}

	@Test
	void aRelationThatWasCutOffSaysSo_andStillShowsTheRowsItHas() {
		List<DetailLine> lines = DetailSections
			.of(detail(Map.of("mountedBy", Relation.of(List.of(pod("one"), pod("two")), true))));

		assertThat(text(lines)).anySatisfy((line) -> assertThat(line)
			.contains("truncated — we stopped at 2; the collection is larger than what you see"));
		assertThat(text(lines)).as("the rows it did get are real and are still drawn")
			.anySatisfy((line) -> assertThat(line).contains("one"))
			.anySatisfy((line) -> assertThat(line).contains("two"));
	}

	@Test
	void aRefusedRelationSaysYouMayNotSeeIt_andDrawsNoTable() {
		List<DetailLine> lines = DetailSections.of(detail(
				Map.of("routedBy", Relation.notPermitted("kweblens is not permitted to read this related kind"))));

		assertThat(text(lines)).anySatisfy((line) -> assertThat(line)
			.contains("not permitted — you may not see this: kweblens is not permitted to read this related kind"));
		assertThat(text(lines)).as("a refusal never renders as 'none' — that would be a claim about the cluster")
			.doesNotContain("    none")
			.doesNotContain("  Routed By (0)");
	}

	/**
	 * The trap {@link Relation}'s own javadoc names: a refusal sets {@code error} as well
	 * as {@code notPermitted}, so a renderer that branches on {@code error} first turns
	 * every expected least-privilege refusal into a malfunction.
	 */
	@Test
	void aRefusalIsNotReportedAsAMalfunction_becauseItCarriesAnErrorToo() {
		Relation refused = Relation.notPermitted("no access to ingresses");
		assertThat(refused.error()).as("the fixture must actually carry both, or this test proves nothing").isNotNull();

		List<DetailLine> lines = DetailSections.of(detail(Map.of("routedBy", refused)));

		assertThat(text(lines)).anySatisfy((line) -> assertThat(line).contains("not permitted"));
		assertThat(text(lines)).noneSatisfy((line) -> assertThat(line).contains("failed"));
	}

	@Test
	void aFailedRelationSaysItFailed_andDrawsNoTable() {
		List<DetailLine> lines = DetailSections.of(detail(Map.of("endpoints", Relation.failed("connection refused"))));

		assertThat(text(lines))
			.anySatisfy((line) -> assertThat(line).contains("failed — this could not be loaded: connection refused"));
	}

	@Test
	void anEmptyRelationIsTheOneThingThatMayReadAsNone() {
		List<DetailLine> lines = DetailSections.of(detail(Map.of("selectedPods", Relation.of(List.of()))));

		assertThat(text(lines)).contains("  Selected Pods (0)", "    none");
	}

	@Test
	void aKindWithNoRelationsSaysThat_ratherThanShowingAnEmptyRelationsBlock() {
		List<DetailLine> lines = DetailSections.of(detail(Map.of()));

		assertThat(text(lines)).contains("  kweblens computes no relations for this kind.");
	}

	@Test
	void relationsAreDrawnInTheOrderTheServerSentThem() {
		Map<String, Relation> ordered = new LinkedHashMap<>();
		ordered.put("endpoints", Relation.of(List.of()));
		ordered.put("routedBy", Relation.of(List.of()));
		ordered.put("selectedPods", Relation.of(List.of()));

		List<String> lines = text(DetailSections.of(detail(ordered)));

		assertThat(lines).containsSubsequence("  Endpoints (0)", "  Routed By (0)", "  Selected Pods (0)");
	}

	/**
	 * The title is derived from the key, so a thirteenth relation added server-side reads
	 * as English on day one instead of appearing as its raw key.
	 */
	@Test
	void titlesAreDerivedFromTheKey_neverTabulated() {
		assertThat(DetailSections.title("ownedBy")).isEqualTo("Owned By");
		assertThat(DetailSections.title("disruptionBudgets")).isEqualTo("Disruption Budgets");
		assertThat(DetailSections.title("endpoints")).isEqualTo("Endpoints");
	}

	@Test
	void aRelationRowNamesTheObject_itsKindAndItsNamespace() {
		List<String> lines = text(
				DetailSections.of(detail(Map.of("selectedPods", Relation.of(List.of(pod("coredns-abc")))))));

		assertThat(lines)
			.anySatisfy((line) -> assertThat(line).contains("NAME").contains("KIND").contains("NAMESPACE"));
		assertThat(lines)
			.anySatisfy((line) -> assertThat(line).contains("coredns-abc").contains("Pod").contains("web"));
	}

	@Test
	void eventsAreDrawnNewestFirstAsTheServerSentThem() {
		ObjectDetail detail = ObjectDetail.of("kind: Pod\n", Map.of(),
				List.of(new EventSummary("Warning", "BackOff", "Pod/web", "web", "Back-off restarting", "2m"),
						new EventSummary("Normal", "Scheduled", "Pod/web", "web", "Assigned to node-1", "9m")));

		List<String> lines = text(DetailSections.of(detail));

		assertThat(lines).contains("EVENTS (2)");
		assertThat(lines).anySatisfy((line) -> assertThat(line).contains("Warning").contains("BackOff").contains("2m"));
	}

	@Test
	void anObjectWithNoEventsSaysNone() {
		assertThat(text(DetailSections.of(detail(Map.of())))).contains("EVENTS (0)", "  none");
	}

	@Test
	void theYamlIsOneLinePerLine_unindentedSoItIsStillYaml() {
		ObjectDetail detail = ObjectDetail.of("apiVersion: v1\nkind: Pod\nmetadata:\n  name: web\n", Map.of(),
				List.of());

		List<String> lines = text(DetailSections.of(detail));

		assertThat(lines).contains("apiVersion: v1", "kind: Pod", "  name: web");
	}

	/**
	 * <b>The header counts the document's lines, and every line it counts is a line of
	 * the document.</b> The producer is asked for its own YAML rather than a hand-written
	 * fixture, because the defect is a property of what it emits: it terminates the
	 * document with a newline, and a renderer that reads that terminator as a separator
	 * reports one line too many and then draws the line it counted — an empty one, which
	 * {@code G} parks the cursor on.
	 */
	@Test
	void theYamlHeaderCountsTheDocumentsOwnLines_andNothingIsDrawnBelowItsLastOne() {
		String yaml = Serialization.asYaml(pod("web"));
		assertThat(yaml).as("the producer terminates the document — that is what is being rendered").endsWith("\n");
		long real = yaml.lines().count();

		List<String> lines = text(DetailSections.of(ObjectDetail.of(yaml, Map.of(), List.of())));

		assertThat(lines).contains("YAML (" + real + " lines)");
		assertThat(lines.get(lines.size() - 1)).as("the last line of the pane is the last line of the document")
			.isEqualTo(yaml.lines().toList().get((int) real - 1));
	}

	/**
	 * Only the terminator goes. A document whose last line is genuinely blank still has
	 * it — which is why the split keeps its {@code -1} limit rather than letting
	 * {@code split} drop every trailing empty it finds.
	 */
	@Test
	void aBlankLastLineOfTheDocumentSurvives_becauseOnlyTheTerminatorIsTheSeparator() {
		List<String> lines = text(DetailSections.of(ObjectDetail.of("a: 1\nb: 2\n\n", Map.of(), List.of())));

		assertThat(lines).contains("YAML (3 lines)");
		assertThat(lines).containsSubsequence("a: 1", "b: 2", "");
	}

	@Test
	void theHeadlineIsTheVerdictFirst_andSaysSoInWordsWhenNothingJudgedIt() {
		assertThat(DetailSections.headline("Running", "Pod", "web", "coredns"))
			.isEqualTo("Running  ·  Pod  ·  web/coredns");
		assertThat(DetailSections.headline(null, "Namespace", "", "team-a"))
			.as("null means nothing judged it — a third answer, not 'OK'")
			.isEqualTo("— no verdict  ·  Namespace  ·  team-a");
	}

	/**
	 * A pane that could not be read is the reason and nothing else. Empty sections would
	 * assert that an object with no relations and no events exists.
	 */
	@Test
	void aDetailThatCouldNotBeReadIsJustTheReason_notEmptySections() {
		List<String> lines = text(DetailSections.of(ObjectDetail.failed("the cluster refused")));

		assertThat(lines).containsExactly("the cluster refused");
	}

	/**
	 * The headline is the frame title's, not the document's — it has to stay on screen
	 * while the document scrolls, and a copy in both places shows the operator one
	 * sentence twice.
	 */
	@Test
	void theDocumentDoesNotRepeatTheHeadline() {
		List<String> lines = text(DetailSections.of(detail(Map.of())));

		assertThat(lines).first(org.assertj.core.api.InstanceOfAssertFactories.STRING).isEqualTo("RELATIONS");
	}

}
