package org.alexmond.kweblens.tui.screen;

import java.time.Instant;

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.GenericKubernetesResourceBuilder;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The projection: identity, age, and what a missing verdict is allowed to look like. */
class ResourceRowTest {

	private static final Instant NOW = Instant.parse("2026-08-15T12:00:00Z");

	private static GenericKubernetesResource object(String namespace, String name, String created) {
		return new GenericKubernetesResourceBuilder().withApiVersion("v1")
			.withKind("Pod")
			.withNewMetadata()
			.withNamespace(namespace)
			.withName(name)
			.withCreationTimestamp(created)
			.endMetadata()
			.build();
	}

	@Test
	void theKeyIsNamespaceAndNameAndIsTheSameForEveryActionOnOneObject() {
		GenericKubernetesResource pod = object("ns", "web-0", null);

		assertThat(ResourceRow.keyOf(pod)).isEqualTo("ns/web-0");
		assertThat(ResourceRow.of(pod, "Running", NOW).key()).isEqualTo(ResourceRow.keyOf(pod));
	}

	@Test
	void anObjectWithNoMetadataStillHasAKeyRatherThanThrowing() {
		assertThat(ResourceRow.keyOf(null)).isEqualTo("/");
		assertThat(ResourceRow.keyOf(new GenericKubernetesResourceBuilder().build())).isEqualTo("/");
	}

	@Test
	void aClusterScopedObjectHasAnEmptyNamespaceRatherThanTheWordDefault() {
		ResourceRow row = ResourceRow.of(object(null, "node-1", null), null, NOW);

		assertThat(row.namespace()).isEmpty();
		assertThat(row.key()).isEqualTo("/node-1");
	}

	@Test
	void ageIsTheLargestUnitThatFits() {
		assertThat(ResourceRow.age("2026-08-15T11:59:31Z", NOW)).isEqualTo("29s");
		assertThat(ResourceRow.age("2026-08-15T11:30:00Z", NOW)).isEqualTo("30m");
		assertThat(ResourceRow.age("2026-08-15T06:00:00Z", NOW)).isEqualTo("6h");
		assertThat(ResourceRow.age("2026-07-16T12:00:00Z", NOW)).isEqualTo("30d");
	}

	@Test
	void anUnknownTimestampIsEmptyAndNotZero() {
		assertThat(ResourceRow.age(null, NOW)).isEmpty();
		assertThat(ResourceRow.age("  ", NOW)).isEmpty();
		assertThat(ResourceRow.age("not-a-timestamp", NOW)).isEmpty();
	}

	@Test
	void aClockSkewedObjectReadsAsBrandNewRatherThanNegative() {
		assertThat(ResourceRow.age("2026-08-15T12:00:30Z", NOW)).isEqualTo("0s");
	}

	@Test
	void noVerdictIsNullRatherThanAnEmptyStringThatCouldReadAsFine() {
		assertThat(ResourceRow.of(object("ns", "a", null), null, NOW).state()).isNull();
	}

}
