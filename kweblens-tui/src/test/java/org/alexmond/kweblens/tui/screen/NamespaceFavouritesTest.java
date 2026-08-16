package org.alexmond.kweblens.tui.screen;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The number keys fill themselves in from where you go, not from a file you edit. */
class NamespaceFavouritesTest {

	private final NamespaceFavourites favourites = new NamespaceFavourites();

	@Test
	void theMostRecentlyVisitedNamespaceIsSlotOne() {
		this.favourites.remember("kube-system");
		this.favourites.remember("default");

		assertThat(this.favourites.at('1')).contains("default");
		assertThat(this.favourites.at('2')).contains("kube-system");
	}

	@Test
	void visitingAgainMovesItBackToTheFrontRatherThanAddingASecondEntry() {
		this.favourites.remember("a");
		this.favourites.remember("b");
		this.favourites.remember("a");

		assertThat(this.favourites.recent()).containsExactly("a", "b");
	}

	@Test
	void zeroIsAlwaysEveryNamespaceAndIsNeverASlot() {
		this.favourites.remember("kube-system");

		assertThat(this.favourites.isAll('0')).isTrue();
		assertThat(this.favourites.at('0')).as("0 is not slot zero; it is the whole cluster").isEmpty();
	}

	@Test
	void anUnusedSlotIsEmptyRatherThanTheClosestThingToIt() {
		this.favourites.remember("only");

		assertThat(this.favourites.at('2')).isEmpty();
		assertThat(this.favourites.at('9')).isEmpty();
	}

	@Test
	void everyNamespaceIsNotRememberedAsANamespace() {
		this.favourites.remember(null);
		this.favourites.remember("  ");

		assertThat(this.favourites.recent()).isEmpty();
	}

	@Test
	void theListStopsAtNineBecauseThereAreOnlyNineKeys() {
		for (int i = 0; i < 20; i++) {
			this.favourites.remember("ns-" + i);
		}

		assertThat(this.favourites.recent()).hasSize(NamespaceFavourites.SLOTS);
		assertThat(this.favourites.at('1')).contains("ns-19");
	}

}
