package org.alexmond.kweblens.column;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.client.utils.Serialization;
import org.junit.jupiter.api.Test;

import org.alexmond.kweblens.resource.PrinterColumn;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the CRD printer-column evaluator does with the JSONPath it does <b>not</b>
 * implement.
 *
 * <p>
 * The forms it does implement are pinned against the SPA by {@code ColumnParityTest}.
 * These are the ones the corpus deliberately does not carry, because they are places
 * where matching JavaScript would mean copying a bad answer: a path that resolves to an
 * object prints {@code [object Object]} in a browser, and that is not a cell value, it is
 * the absence of one wearing a costume.
 */
class PrinterColumnsTest {

	private static final Instant NOW = Instant.parse("2026-01-15T12:00:00Z");

	private static final String OBJECT = """
			apiVersion: helm.cattle.io/v1
			kind: HelmChart
			metadata:
			  name: traefik
			spec:
			  chart:
			    name: traefik
			status:
			  conditions:
			  - type: Ready
			    status: "True"
			  replicas: 3
			""";

	private final GenericKubernetesResource object = Serialization.unmarshal(OBJECT, GenericKubernetesResource.class);

	private String render(String jsonPath, String type) {
		return PrinterColumns.render(this.object, jsonPath, type, NOW);
	}

	@Test
	void anUnimplementedFormFindsNothingAndSaysSo() {
		assertThat(render(".status.conditions[*].type", "string")).isEqualTo(ColumnCatalog.MISSING_CELL);
		assertThat(render(".status.conditions[?(@.status!=\"True\")].type", "string"))
			.isEqualTo(ColumnCatalog.MISSING_CELL);
		assertThat(render("..name", "string")).isEqualTo(ColumnCatalog.MISSING_CELL);
	}

	/**
	 * The one deliberate divergence from the SPA — see the class javadoc on the
	 * evaluator.
	 */
	@Test
	void aPathThatLandsOnAnObjectOrAnArrayIsACellWithNothingInIt() {
		assertThat(render(".spec.chart", "string")).isEqualTo(ColumnCatalog.MISSING_CELL);
		assertThat(render(".status.conditions", "string")).isEqualTo(ColumnCatalog.MISSING_CELL);
		assertThat(render(".status.conditions[?(@.type==\"Ready\")]", "string")).isEqualTo(ColumnCatalog.MISSING_CELL);
	}

	@Test
	void anEqualityFilterComparesStringsBecauseTheSpaUsesTripleEquals() {
		assertThat(render(".status.conditions[?(@.type==\"Ready\")].status", "string")).isEqualTo("True");
		assertThat(render(".status.conditions[?(@.type==\"Nope\")].status", "string"))
			.isEqualTo(ColumnCatalog.MISSING_CELL);
	}

	@Test
	void aColumnWithNoPathAtAllIsKeyedByItsNameAndRendersNothing() {
		List<Column> columns = PrinterColumns.of(List.of(new PrinterColumn("Blank", "", "string")),
				Clock.fixed(NOW, ZoneOffset.UTC));

		assertThat(columns).singleElement().satisfies((column) -> {
			assertThat(column.key()).isEqualTo("Blank");
			assertThat(column.header()).isEqualTo("Blank");
			assertThat(column.render(this.object)).isEqualTo(ColumnCatalog.MISSING_CELL);
		});
	}

	@Test
	void noDeclaredColumnsIsNoColumns() {
		assertThat(PrinterColumns.of(List.of(), Clock.systemUTC())).isEmpty();
		assertThat(PrinterColumns.of(null, Clock.systemUTC())).isEmpty();
	}

}
