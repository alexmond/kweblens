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
import org.alexmond.kweblens.resource.ResourceDescriptor;

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
 *
 * <p>
 * <b>And every kind is resolved the way its consumers resolve it</b> (GH#460). The corpus
 * is written in the SPA's vocabulary, so this file used to ask the catalog for
 * {@code forResourceId("deployments")} and never read the {@code (group, kind)} half of
 * the entry — which is the only half the TUI looks a kind up by. A wrong API group on an
 * entry was therefore green here, green in {@code columnParity.test.ts}, and a terminal
 * drawing no columns at all. So {@link #everyLookup} names every lookup the catalog
 * offers, each case is resolved through all of them, and they must agree; a lookup added
 * to {@code ColumnCatalog} is one line there and is then exercised by every corpus case.
 * The group is taken from the object's <b>own</b> {@code apiVersion}, never from the
 * catalog, because two indexes built from one literal agree with each other whatever that
 * literal says.
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
			GenericKubernetesResource object = object(declared);
			Map<String, String> rendered = render(columns(kind, object, name), object);
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

	/**
	 * Which is also what makes the lookup check above cover the whole catalog: every
	 * entry has at least one case, and every case resolves that entry through every
	 * lookup.
	 */
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

	/**
	 * The columns for one corpus case, resolved through every lookup and asserted to be
	 * the same list each time. An empty answer from any of them is the failure the TUI
	 * sees as a table with no kind-specific columns in it.
	 */
	private static List<Column> columns(String resourceId, GenericKubernetesResource object, String name) {
		Map<String, List<Column>> found = everyLookup(resourceId, object);
		String reference = found.keySet().iterator().next();
		List<Column> resolved = found.get(reference);
		found.forEach((lookup, columns) -> {
			assertThat(columns).as(name + ": " + lookup + " found no server-side columns").isNotEmpty();
			assertThat(columns).as(name + ": " + lookup + " and " + reference + " resolve different columns")
				.isEqualTo(resolved);
		});
		return resolved;
	}

	/**
	 * Every way a consumer asks the catalog for a kind's columns, keyed by a description
	 * of the call so a disagreement names which one. {@code forResourceId} is the SPA's
	 * vocabulary and {@code forDescriptor} is what {@code CoreClusterDataSource} calls
	 * with a descriptor API discovery built. <b>A lookup added to {@code ColumnCatalog}
	 * is added here</b>, and every corpus case then exercises it.
	 */
	private static Map<String, List<Column>> everyLookup(String resourceId, GenericKubernetesResource object) {
		Map<String, List<Column>> found = new LinkedHashMap<>();
		found.put("forResourceId(" + resourceId + ")", ColumnCatalog.forResourceId(resourceId));
		found.put("forDescriptor(" + object.getApiVersion() + " " + object.getKind() + ")",
				ColumnCatalog.forDescriptor(discovered(object, resourceId)));
		return found;
	}

	/**
	 * The descriptor API discovery would hand the catalog for this object, with the group
	 * and version split out of the object's <b>own</b> {@code apiVersion} — the one
	 * statement of the kind's coordinates in this corpus that the catalog did not write.
	 */
	private static ResourceDescriptor discovered(GenericKubernetesResource object, String resourceId) {
		String apiVersion = object.getApiVersion();
		assertThat(apiVersion).as("a corpus object has to declare the apiVersion it is addressed by").isNotBlank();
		int slash = apiVersion.indexOf('/');
		String group = (slash < 0) ? "" : apiVersion.substring(0, slash);
		String version = (slash < 0) ? apiVersion : apiVersion.substring(slash + 1);
		return new ResourceDescriptor(resourceId, object.getKind(), object.getKind(), group, version, resourceId, true,
				false);
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
