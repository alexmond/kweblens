package org.alexmond.kweblens.tui.render;

import java.time.Instant;
import java.util.List;

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.client.utils.Serialization;
import org.junit.jupiter.api.Test;

import org.alexmond.kweblens.column.Column;
import org.alexmond.kweblens.column.ColumnCatalog;
import org.alexmond.kweblens.resource.PrinterColumn;
import org.alexmond.kweblens.column.PrinterColumns;
import org.alexmond.kweblens.tui.screen.ColumnLayout;
import org.alexmond.kweblens.tui.screen.ResourceRow;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a row actually says, cell by cell.
 *
 * <p>
 * The loop tests draw through the real TamboUI renderer, but {@code FakeBackend} records
 * that a frame happened rather than what was in it — so "the terminal shows READY" is not
 * something they can assert. These read the strings the table is built from, which is the
 * closest thing to the operator's eye this module has without a pty ({@code
 * scripts/tui-drive.sh} is the last inch, on demand and not a gate).
 */
class ResourceTableViewTest {

	private static final Instant NOW = Instant.parse("2026-01-15T12:00:00Z");

	private static final String POD = """
			apiVersion: v1
			kind: Pod
			metadata:
			  namespace: shop
			  name: web-0
			  creationTimestamp: "2026-01-13T12:00:00Z"
			spec:
			  nodeName: node-a
			status:
			  containerStatuses:
			  - name: app
			    ready: true
			    restartCount: 3
			  - name: sidecar
			    ready: false
			""";

	/** A Pod that has not been scheduled: the path the NODE column reads is not there. */
	private static final String UNSCHEDULED = """
			apiVersion: v1
			kind: Pod
			metadata:
			  namespace: shop
			  name: queued
			  creationTimestamp: "2026-01-15T11:59:00Z"
			status:
			  phase: Pending
			""";

	private final ResourceTableView view = new ResourceTableView();

	private ColumnLayout layout(List<Column> columns) {
		this.view.headers(columns.stream().map(Column::header).toList());
		return ColumnLayout.forWidth(132, true, columns.stream().map(Column::header).toList());
	}

	private static ResourceRow row(String manifest, String state, List<Column> columns) {
		GenericKubernetesResource object = Serialization.unmarshal(manifest, GenericKubernetesResource.class);
		return ResourceRow.of(object, state, NOW, columns);
	}

	@Test
	void aPodRowCarriesTheServerComputedCellsInColumnOrder() {
		List<Column> columns = ColumnCatalog.forResourceId("pods");

		assertThat(this.view.cells(row(POD, "Running", columns), layout(columns))).containsExactly("shop", "web-0",
				"Running", "1/2", "3", "node-a", "2d");
	}

	@Test
	void theHeadingsAreTheTerminalsOwnUpperCaseAndSitBetweenTheVerdictAndTheAge() {
		List<Column> columns = ColumnCatalog.forResourceId("pods");

		assertThat(this.view.headings(layout(columns))).containsExactly("NAMESPACE", "NAME", "STATE", "READY",
				"RESTARTS", "NODE", "AGE");
	}

	/**
	 * The done-when this test exists for: a path the object does not carry renders an em
	 * dash. An empty cell under a heading is the claim that the Pod is on a node with no
	 * name.
	 */
	@Test
	void aPathTheObjectDoesNotCarryIsADashAndNotABlank() {
		List<Column> columns = ColumnCatalog.forResourceId("pods");

		assertThat(this.view.cells(row(UNSCHEDULED, null, columns), layout(columns))).containsExactly("shop", "queued",
				"—", "0/0", "0", "—", "1m");
	}

	/**
	 * For one tick after a kind switch the model can still hold rows projected against
	 * the previous kind — fewer cells than the new headings. They are drawn as dashes for
	 * the same reason: a blank says the object has nothing there, and this row was never
	 * asked.
	 */
	@Test
	void aRowShorterThanTheHeadingsIsDashedNotPadded() {
		List<Column> columns = ColumnCatalog.forResourceId("pods");
		ResourceRow stale = new ResourceRow("shop/old", "shop", "old", "Running", "9d");

		assertThat(this.view.cells(stale, layout(columns))).containsExactly("shop", "old", "Running", "—", "—", "—",
				"9d");
	}

	/**
	 * A CRD's own columns reach the table with <b>no code change</b>: they are read off
	 * the CRD, evaluated by the same path machinery, and drawn by the same table.
	 */
	@Test
	void aCustomKindsDeclaredColumnsAreDrawnLikeAnyOther() {
		List<Column> columns = PrinterColumns.of(
				List.of(new PrinterColumn("Chart", ".spec.chart", "string"),
						new PrinterColumn("Phase", ".status.phase", "string")),
				java.time.Clock.fixed(NOW, java.time.ZoneOffset.UTC));
		String manifest = """
				apiVersion: helm.cattle.io/v1
				kind: HelmChart
				metadata:
				  namespace: kube-system
				  name: traefik
				  creationTimestamp: "2026-01-15T11:00:00Z"
				spec:
				  chart: traefik
				status:
				  phase: Deployed
				""";

		assertThat(this.view.headings(layout(columns))).contains("CHART", "PHASE");
		assertThat(this.view.cells(row(manifest, null, columns), layout(columns))).containsExactly("kube-system",
				"traefik", "—", "traefik", "Deployed", "1h");
	}

}
