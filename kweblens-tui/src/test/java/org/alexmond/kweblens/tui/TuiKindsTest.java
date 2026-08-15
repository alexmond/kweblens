package org.alexmond.kweblens.tui;

import org.junit.jupiter.api.Test;

import org.alexmond.kweblens.resource.ResourceDescriptor;
import org.alexmond.kweblens.resource.WellKnownKinds;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The placeholder kind lookup. Every descriptor it hands back must be
 * {@code kweblens-core}'s own — the moment one is spelled here instead, this module has a
 * second catalog that can drift from the server's.
 */
class TuiKindsTest {

	@Test
	void aKnownIdResolvesToCoresOwnDescriptor() {
		assertThat(TuiKinds.byId("pods")).contains(WellKnownKinds.PODS);
		assertThat(TuiKinds.byId("namespaces")).contains(WellKnownKinds.NAMESPACES);
	}

	@Test
	void lookupIsForgivingAboutCaseAndSurroundingSpace() {
		assertThat(TuiKinds.byId("  PODS ")).contains(WellKnownKinds.PODS);
	}

	@Test
	void anUnknownOrMissingIdIsEmptyRatherThanAGuess() {
		assertThat(TuiKinds.byId("widgets")).isEmpty();
		assertThat(TuiKinds.byId(null)).isEmpty();
	}

	@Test
	void everyIdListedCanBeResolved() {
		assertThat(TuiKinds.ids()).isNotEmpty()
			.allSatisfy((id) -> assertThat(TuiKinds.byId(id)).isPresent())
			.isSorted();
	}

	@Test
	void idsMatchTheDescriptorsTheyResolveTo() {
		for (String id : TuiKinds.ids()) {
			assertThat(TuiKinds.byId(id).map(ResourceDescriptor::id)).contains(id);
		}
	}

}
