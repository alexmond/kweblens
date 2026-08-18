package org.alexmond.kweblens.tui;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>The gate for "the TUI computes no relation of its own"</b> (GH#368).
 *
 * <p>
 * {@code RelationService} owns twelve joins — which pods back this Service, what mounts
 * this Secret, what created this pod — and its javadoc says why they are in
 * {@code kweblens-core}: <i>"Doing the joins here means one implementation serves the
 * SPA, a future TUI and the agent tool surface, instead of each reimplementing them."</i>
 * That sentence is the argument for building this terminal on kweblens rather than
 * porting k9s, and it is only true for as long as nobody adds a second implementation. A
 * second one would not arrive as a decision; it would arrive as three helpful lines in a
 * renderer that "just needed the owner's name".
 *
 * <h2>Why this reads bytes and not source</h2>
 *
 * Two reasons, and the first is embarrassing to discover by hand.
 *
 * <ul>
 * <li><b>A source grep matches this file's own prose.</b> Every explanation of the rule —
 * this javadoc, {@code ObjectDetail}'s, {@code DetailSections}' — has to name
 * {@code ownerReferences} to say what is forbidden. A grep over sources therefore reports
 * the documentation of the rule as a violation of it, and the only way to keep it green
 * is to stop explaining. A constant pool holds code, not comments.</li>
 * <li><b>The precedent is bytecode for a harder reason.</b>
 * {@code SseEndpointKeepAliveTest} scans for an {@code invokestatic} because a name
 * search passed a class that attached nothing, and {@code McpToolsNeverCallAModelTest}
 * scans bytes because an interface only constrains the field somebody declared.</li>
 * </ul>
 *
 * <p>
 * <b>And a shell {@code grep} would not have worked either.</b> Measured on this repo:
 * {@code grep -c matchLabels target/classes/.../DrillDown.class} exits 1 — no match, no
 * warning — while {@code grep -ac} on the same file answers 2. A class file is binary to
 * grep, so a scanner built on it reports every module clean. Reading the bytes as
 * ISO-8859-1 in Java, as the two tests above do, has no such mode.
 *
 * <h2>How this was proved to fail</h2>
 *
 * Recorded here because a green gate that has never fired pins nothing — see the
 * per-assertion notes.
 */
class TuiComputesNoRelationTest {

	/** Every shipped class of this module. */
	private static final String TUI = "org/alexmond/kweblens/tui/";

	/**
	 * The class that owns the twelve joins, and therefore the only route to a relation.
	 */
	private static final String RELATION_SERVICE = "org/alexmond/kweblens/resource/RelationService";

	/**
	 * The fields the twelve relations are computed <em>from</em>. Naming one of these is
	 * what performing a join looks like: {@code ownerReferences} is {@code ownedBy} and
	 * {@code replicaSets}, {@code matchLabels} is {@code selectedPods},
	 * {@code serviceAccountName} is {@code serviceAccount}, {@code subsets} is
	 * {@code endpoints}, {@code claimRef}/{@code volumeName} are the storage pair,
	 * {@code scaleTargetRef} is {@code autoscaledBy}, {@code involvedObject} is the event
	 * join.
	 */
	private static final List<String> JOIN_INPUTS = List.of("ownerReferences", "getOwnerReferences", "matchLabels",
			"matchExpressions", "involvedObject", "serviceAccountName", "getServiceAccountName", "scaleTargetRef",
			"claimRef", "volumeName", "subsets", "getSubsets");

	/**
	 * The one class allowed to read a join input, and the reason is on it:
	 * {@code DrillDown} turns a workload's {@code matchLabels} into a <b>filter query the
	 * operator can see and edit</b> — {@code pods(kube-system) </k8s-app=kube-dns>} —
	 * which is the opposite of a hidden join, and it is #366's decided design. It is
	 * blessed by name rather than by pattern so that adding a second exception is a
	 * deliberate edit here.
	 */
	private static final String DRILL_DOWN = "screen/DrillDown.class";

	/**
	 * The one class allowed to name {@code RelationService}: the port's single adapter.
	 */
	private static final String ADAPTER = "data/CoreClusterDataSource.class";

	/**
	 * <b>Made to fail:</b> a line reading
	 * {@code object.getMetadata().getOwnerReferences()} was added to
	 * {@code DetailSections.row} — the shape a "helpful" relation renderer takes. This
	 * failed naming
	 * {@code org/alexmond/kweblens/tui/detail/DetailSections.class names 'getOwnerReferences'}
	 * and passed again the moment the line was removed.
	 */
	@Test
	void nothingButTheVisibleFilterEvenNamesAJoinInput() throws IOException {
		for (String marker : JOIN_INPUTS) {
			assertThat(referrers(marker)).as("""
					A kweblens-tui class names '%s' — a field one of RelationService's twelve joins is \
					computed from. The joins live in kweblens-core so that the SPA, this terminal and \
					the agent tools give one answer; a second implementation here is how they start \
					giving two. Ask ClusterDataSource.detail for the server's relations instead.""".formatted(marker))
				.allSatisfy((found) -> assertThat(found).endsWith(DRILL_DOWN));
		}
	}

	/**
	 * The blessing has to keep earning itself. An exclusion that has stopped matching a
	 * real file is itself a failure — the same rule
	 * {@code TrackedSourcesStayGreppableTest} keeps — so if {@code DrillDown} ever stops
	 * expressing a relationship as a query, the exception above must be re-decided rather
	 * than left standing over nothing.
	 */
	@Test
	void theOneExceptionIsStillTheClassItWasGrantedFor() throws IOException {
		assertThat(referrers("matchLabels"))
			.as("DrillDown is the exception because it turns a selector into a "
					+ "VISIBLE, editable filter; if it no longer reads one, the exception is stale")
			.singleElement(org.assertj.core.api.InstanceOfAssertFactories.STRING)
			.endsWith(DRILL_DOWN);
	}

	/**
	 * <b>Made to fail:</b> a {@code RelationService relations;} field was added to
	 * {@code ScreenSession}. This failed naming
	 * {@code org/alexmond/kweblens/tui/render/ScreenSession.class}, which is exactly the
	 * class a "the pane needs the relations, let me just inject it" change would touch.
	 */
	@Test
	void onlyThePortsOneAdapterCanEvenNameTheClassThatOwnsTheJoins() throws IOException {
		assertThat(referrers(RELATION_SERVICE)).as("""
				RelationService is reachable from a kweblens-tui class other than the port's single \
				adapter. Relations enter this module through ClusterDataSource.detail and nowhere \
				else — that is what makes an HTTP adapter possible later, and what stops a renderer \
				from quietly asking the cluster something of its own.""")
			.allSatisfy((found) -> assertThat(found).endsWith(ADAPTER));
	}

	/** The slice that draws relations is the slice most likely to grow one. */
	@Test
	void theRenderingSliceNamesNeitherTheJoinsNorTheirInputs() throws IOException {
		List<String> offenders = new ArrayList<>();
		for (String marker : JOIN_INPUTS) {
			offenders.addAll(referrers(marker).stream().filter((found) -> found.contains("/detail/")).toList());
		}
		offenders.addAll(referrers(RELATION_SERVICE).stream().filter((found) -> found.contains("/detail/")).toList());

		assertThat(offenders).as("the detail pane renders the server's relations; it does not compute any").isEmpty();
	}

	/**
	 * Positive control. A scanner that read no classes, or that matched no bytes, would
	 * report the whole module clean and pass every assertion above for the wrong reason —
	 * so prove it can see the two references that are known to exist, in the two classes
	 * that are known to hold them.
	 */
	@Test
	void theScanCanSeeAReferenceWhereOneIsKnownToExist() throws IOException {
		assertThat(referrers("matchLabels")).as("DrillDown reads a workload's matchLabels to write the filter")
			.isNotEmpty();
		assertThat(referrers(RELATION_SERVICE)).as("the adapter is the sanctioned caller and must stay visible here")
			.isNotEmpty();
		assertThat(referrers("ClusterDataSource")).as("a marker every class in the data package carries")
			.hasSizeGreaterThan(1);
	}

	/** Shipped classes of this module whose bytes mention {@code marker}. */
	private static List<String> referrers(String marker) throws IOException {
		List<String> found = new ArrayList<>();
		Resource[] classes = new PathMatchingResourcePatternResolver().getResources("classpath*:" + TUI + "**/*.class");
		assertThat(classes).as("no compiled classes found under %s; the scan did not run", TUI).isNotEmpty();
		for (Resource resource : classes) {
			String location = resource.getURL().toString();
			// Test classes are not shipped, and this file itself names every marker.
			if (location.contains("test-classes")) {
				continue;
			}
			try (InputStream in = resource.getInputStream()) {
				String bytes = new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
				if (bytes.contains(marker)) {
					found.add(location.substring(location.indexOf(TUI)));
				}
			}
		}
		return found;
	}

}
