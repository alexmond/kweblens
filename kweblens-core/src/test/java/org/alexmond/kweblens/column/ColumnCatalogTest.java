package org.alexmond.kweblens.column;

import java.time.Instant;
import java.util.List;

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.client.utils.Serialization;
import org.junit.jupiter.api.Test;

import org.alexmond.kweblens.resource.ResourceDescriptor;
import org.alexmond.kweblens.resource.WellKnownKinds;

import static org.assertj.core.api.Assertions.assertThat;

/** How a consumer finds a kind's columns, and what an uncovered kind gets. */
class ColumnCatalogTest {

	@Test
	void aCoveredKindIsFoundByItsGroupAndKindNotByAnId() {
		assertThat(ColumnCatalog.forDescriptor(WellKnownKinds.PODS).stream().map(Column::key)).containsExactly("ready",
				"restarts", "node");
		assertThat(ColumnCatalog.forResourceId("deployments").stream().map(Column::header)).containsExactly("Ready",
				"Up-to-date", "Available");
	}

	/**
	 * Discovery builds its own descriptors, so the id is whatever the discovery path
	 * assembled and the group and kind are what the API server published. Keying on the
	 * second pair is what makes the catalog answer for a kind the TUI resolved from
	 * {@code :po} as well as one the SPA routed to as {@code pods}.
	 */
	@Test
	void aDescriptorBuiltByDiscoveryFindsTheSameColumns() {
		ResourceDescriptor discovered = new ResourceDescriptor("pods", "Pod", "Pod", "", "v1", "pods", true, false);

		assertThat(ColumnCatalog.forDescriptor(discovered)).isEqualTo(ColumnCatalog.forDescriptor(WellKnownKinds.PODS));
	}

	@Test
	void anUncoveredKindHasNoColumnsRatherThanAGuessAtSome() {
		assertThat(ColumnCatalog.forDescriptor(WellKnownKinds.SECRETS)).isEmpty();
		assertThat(ColumnCatalog.forResourceId("nothing-of-the-sort")).isEmpty();
		assertThat(ColumnCatalog.forDescriptor(null)).isEmpty();
		assertThat(ColumnCatalog.values(List.of(), pod())).isEmpty();
	}

	@Test
	void theTrancheIsTheFiveKindsATerminalOpensFirst() {
		assertThat(ColumnCatalog.coveredResourceIds()).containsExactly("pods", "deployments", "nodes", "services",
				"events");
	}

	@Test
	void valuesComeBackInColumnOrderSoAConsumerCanHoldThemPositionally() {
		assertThat(ColumnCatalog.values(ColumnCatalog.forResourceId("pods"), pod())).containsExactly("1/1", "2",
				"node-a");
	}

	@Test
	void anEventKindHasNoStatusColumnBecauseItsTypeIsNotAVerdictOnIt() {
		assertThat(ColumnCatalog.forResourceId("events").stream().map(Column::key)).doesNotContain("status");
	}

	@Test
	void agesAreMeasuredAgainstAGivenMomentAndAMissingOneIsADash() {
		Instant now = Instant.parse("2026-01-15T12:00:00Z");

		assertThat(Ages.of("2026-01-13T12:00:00Z", now)).isEqualTo("2d");
		assertThat(Ages.of("2026-01-15T10:00:00Z", now)).isEqualTo("2h");
		assertThat(Ages.of("2026-01-15T11:58:00Z", now)).isEqualTo("2m");
		assertThat(Ages.of("2026-01-15T11:59:58Z", now)).isEqualTo("2s");
		assertThat(Ages.of("2026-01-15T13:00:00Z", now)).as("a clock skewed forward is not a negative age")
			.isEqualTo("0s");
		assertThat(Ages.of(null, now)).isEqualTo(ColumnCatalog.MISSING_CELL);
		assertThat(Ages.of("not a timestamp", now)).isEqualTo(ColumnCatalog.MISSING_CELL);
	}

	private static GenericKubernetesResource pod() {
		return Serialization.unmarshal("""
				apiVersion: v1
				kind: Pod
				metadata:
				  name: web-0
				spec:
				  nodeName: node-a
				status:
				  containerStatuses:
				  - name: app
				    ready: true
				    restartCount: 2
				""", GenericKubernetesResource.class);
	}

}
