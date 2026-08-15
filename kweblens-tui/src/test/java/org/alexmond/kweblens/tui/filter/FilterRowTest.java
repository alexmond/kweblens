package org.alexmond.kweblens.tui.filter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.GenericKubernetesResourceBuilder;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import org.junit.jupiter.api.Test;

import org.alexmond.kweblens.health.ObjectState;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The seam: projecting what {@code ClusterDataSource.list} and
 * {@code ClusterDataSource.states} hand back into the five things the grammar can ask
 * about.
 *
 * <p>
 * This is the only place the filter package touches fabric8, and it is here rather than
 * in the screen so that #364 and #365 wire one call rather than five field reads each.
 */
class FilterRowTest {

	@Test
	void projectsAListedObjectAndItsVerdict() {
		GenericKubernetesResource pod = pod("web-1", "prod", Map.of("app", "web"));
		FilterRow row = FilterRow.of(pod, Optional.of(new ObjectState("Running", "ok")));

		assertThat(row.name()).isEqualTo("web-1");
		assertThat(row.namespace()).isEqualTo("prod");
		assertThat(row.kind()).isEqualTo("Pod");
		assertThat(row.labels()).containsEntry("app", "web");
		assertThat(row.state()).isEqualTo("Running");
		assertThat(ObjectFilter.parse("status:Running app=web ns:prod").matches(row)).isTrue();
	}

	/**
	 * A kind nothing judges carries no state — which is a third answer, not "OK", and
	 * {@code status:} must refuse to select it however permissive the pattern.
	 */
	@Test
	void anObjectNothingJudgedCarriesNoState() {
		FilterRow row = FilterRow.of(pod("ev-1", "prod", Map.of()), Optional.empty());
		assertThat(row.state()).isEmpty();
		assertThat(ObjectFilter.parse("status:/.*/").matches(row)).isFalse();
	}

	/** A cluster-scoped object has no namespace, and that is {@code ""}, never null. */
	@Test
	void survivesAnObjectWithNoMetadataAtAll() {
		GenericKubernetesResource bare = new GenericKubernetesResourceBuilder().withKind("Node").build();
		FilterRow row = FilterRow.of(bare, Optional.empty());

		assertThat(row.name()).isEmpty();
		assertThat(row.namespace()).isEmpty();
		assertThat(row.kind()).isEqualTo("Node");
		assertThat(row.labels()).isEmpty();
		assertThatCode(() -> ObjectFilter.parse("web app=x label:y").matches(row)).doesNotThrowAnyException();
	}

	@Test
	void toleratesANullObjectAndANullVerdictRatherThanThrowingInsideAKeystroke() {
		FilterRow row = FilterRow.of(null, null);
		assertThat(row.name()).isEmpty();
		assertThat(row.kind()).isEmpty();
		assertThat(row.state()).isEmpty();
	}

	@Test
	void theProjectionIsUnmodifiable() {
		FilterRow row = FilterRow.of(pod("a", "b", Map.of("k", "v")), Optional.empty());
		assertThatThrownBy(() -> row.labels().put("x", "y")).isInstanceOf(UnsupportedOperationException.class);
	}

	/**
	 * The list endpoint returns the whole collection (#302), so the filter is the only
	 * thing between the operator and three thousand rows. Parsing happens once per query,
	 * not once per object: the assertion is that the work stays linear and cheap enough
	 * to run on a keystroke. It is a floor, not a benchmark.
	 */
	@Test
	void filtersThreeThousandObjectsWithoutCompilingARegexPerRow() {
		List<FilterRow> many = new ArrayList<>(3_000);
		for (int i = 0; i < 3_000; i++) {
			many.add(Rows.pod("web-" + i, "prod", Map.of("app", (i % 2 == 0) ? "web" : "db")));
		}
		ParsedFilter filter = ObjectFilter.parse("/^web-\\d+$/ app=web -web-1000");

		long started = System.nanoTime();
		int hits = 0;
		for (FilterRow row : many) {
			if (filter.matches(row)) {
				hits++;
			}
		}
		long elapsedMs = (System.nanoTime() - started) / 1_000_000L;

		assertThat(hits).isEqualTo(1_499);
		assertThat(elapsedMs).isLessThan(500L);
	}

	private static GenericKubernetesResource pod(String name, String namespace, Map<String, String> labels) {
		return new GenericKubernetesResourceBuilder().withApiVersion("v1")
			.withKind("Pod")
			.withMetadata(new ObjectMetaBuilder().withName(name).withNamespace(namespace).withLabels(labels).build())
			.build();
	}

}
