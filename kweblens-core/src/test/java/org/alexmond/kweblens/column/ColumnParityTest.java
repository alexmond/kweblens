package org.alexmond.kweblens.column;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.client.utils.Serialization;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import org.alexmond.kweblens.resource.PrinterColumn;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Java half of the column-parity gate: the same objects, through this package, must
 * produce the same strings the SPA produces.
 *
 * <p>
 * <b>Nothing in this file writes an expectation.</b> That is the whole design.
 * {@code column-parity/expected.json} is rendered by {@code columns.ts} in
 * {@code kweblens-ui/src/columnParity.test.ts}, and both sides then assert against it —
 * so a migration that quietly re-decides what {@code Ready} means goes red here, and a
 * change to the SPA goes red there first and here second. Two hand-written expectations
 * that agree would prove only that one author held one idea twice.
 *
 * <p>
 * The other half of the gate is the coverage assertion: the keys this package renders
 * must be exactly the keys the golden carries, so a column added to one side and
 * forgotten on the other is a failure rather than a value nobody compares.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ColumnParityTest {

	private final JsonNode corpus = read("objects.json");

	private final JsonNode golden = read("expected.json");

	@Test
	void everyKindRendersWhatTheSpaRenders() {
		List<String> compared = new ArrayList<>();
		for (int i = 0; i < this.corpus.get("cases").size(); i++) {
			JsonNode declared = this.corpus.get("cases").get(i);
			JsonNode expected = this.golden.get("cases").get(i);
			String kind = declared.get("kind").asText();
			String name = kind + " / " + declared.get("name").asText();
			assertThat(expected.get("kind").asText()).as("corpus and golden out of step at " + name).isEqualTo(kind);
			List<Column> columns = ColumnCatalog.forResourceId(kind);
			assertThat(columns).as("no server-side columns for " + kind).isNotEmpty();
			Map<String, String> rendered = render(columns, object(declared));
			// Keys first, and separately: a column the SPA has and this package does not
			// is
			// the failure most likely to be read as "some value is wrong" from a diff of
			// fifteen strings.
			assertThat(rendered.keySet()).as("columns covered for " + name)
				.containsExactlyInAnyOrderElementsOf(strings(expected.get("values")).keySet());
			assertThat(rendered).as(name).isEqualTo(strings(expected.get("values")));
			compared.add(name);
		}
		assertThat(compared).hasSize(this.golden.get("cases").size());
	}

	@Test
	void everyCoveredKindAppearsInTheCorpus() {
		List<String> inCorpus = new ArrayList<>();
		this.corpus.get("cases").forEach((node) -> inCorpus.add(node.get("kind").asText()));
		assertThat(inCorpus).as("a covered kind with no corpus case is a kind nothing compares")
			.containsAll(ColumnCatalog.coveredResourceIds());
	}

	@Test
	void crdPrinterColumnsRenderWhatTheSpaRenders() {
		Clock clock = Clock.fixed(Instant.parse(this.corpus.get("now").asText()), ZoneOffset.UTC);
		for (int i = 0; i < this.corpus.get("printerColumns").size(); i++) {
			JsonNode declared = this.corpus.get("printerColumns").get(i);
			JsonNode expected = this.golden.get("printerColumns").get(i);
			List<PrinterColumn> columns = new ArrayList<>();
			declared.get("columns")
				.forEach((column) -> columns.add(new PrinterColumn(column.get("name").asText(),
						column.get("jsonPath").asText(), column.get("type").asText())));
			assertThat(render(PrinterColumns.of(columns, clock), object(declared))).as(declared.get("name").asText())
				.isEqualTo(strings(expected.get("values")));
		}
	}

	private static Map<String, String> render(List<Column> columns, GenericKubernetesResource object) {
		Map<String, String> values = new LinkedHashMap<>();
		for (Column column : columns) {
			values.put(column.key(), column.render(object));
		}
		return values;
	}

	private static Map<String, String> strings(JsonNode node) {
		Map<String, String> values = new LinkedHashMap<>();
		node.properties().forEach((entry) -> values.put(entry.getKey(), entry.getValue().asText()));
		return values;
	}

	/**
	 * Through the client's own deserializer, not a bare Jackson mapper — the numeric and
	 * map types a column renderer meets at runtime are the ones fabric8 produced, and a
	 * test that built them another way would be measuring its own fixture loader.
	 */
	private static GenericKubernetesResource object(JsonNode node) {
		return Serialization.unmarshal(node.get("object").toString(), GenericKubernetesResource.class);
	}

	private static JsonNode read(String name) {
		try {
			return new ObjectMapper().readTree(Files.readString(corpus().resolve(name)));
		}
		catch (IOException ex) {
			throw new IllegalStateException("cannot read the parity corpus: " + name, ex);
		}
	}

	/**
	 * The corpus lives at the repository root because it belongs to neither module. Found
	 * by walking up rather than by a fixed {@code ../}, so this test does not depend on
	 * which directory the build happened to start it in — a path that resolved to nothing
	 * would make the whole file pass by never comparing anything.
	 */
	private static Path corpus() {
		Path dir = Paths.get("").toAbsolutePath();
		for (int up = 0; up < 6 && dir != null; up++) {
			Path candidate = dir.resolve("column-parity");
			if (Files.isRegularFile(candidate.resolve("objects.json"))) {
				return candidate;
			}
			dir = dir.getParent();
		}
		throw new IllegalStateException("column-parity/ not found above " + Paths.get("").toAbsolutePath());
	}

}
