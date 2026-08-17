package org.alexmond.kweblens.tui.screen;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Column widths at the three widths the GH#361 spike actually resized a pty to, plus the
 * degenerate ones.
 *
 * <p>
 * <b>Never 80×24.</b> The three real sizes are 132, 100 and 170 because a layout that was
 * never recomputed and a layout that was recomputed correctly are indistinguishable at
 * whatever width the code happened to start with.
 */
class ColumnLayoutTest {

	@Test
	void eachWidthGetsItsOwnLayoutAndTheNameTakesTheSlack() {
		ColumnLayout wide = ColumnLayout.forWidth(170);
		ColumnLayout medium = ColumnLayout.forWidth(132);
		ColumnLayout narrow = ColumnLayout.forWidth(100);

		assertThat(wide.name()).isGreaterThan(medium.name()).isGreaterThan(narrow.name());
		assertThat(medium.name()).isGreaterThan(narrow.name());
		assertThat(wide).isNotEqualTo(medium);
		assertThat(medium).isNotEqualTo(narrow);
	}

	@Test
	void aLayoutNeverOverflowsTheWidthItWasAskedFor() {
		for (int width = 1; width <= 200; width++) {
			assertThat(ColumnLayout.forWidth(width).total()).as("layout at width %d must fit in %d cells", width, width)
				.isLessThanOrEqualTo(width);
		}
	}

	@Test
	void columnsAreGivenUpFromTheRightSoTheNameSurvivesLongest() {
		assertThat(ColumnLayout.forWidth(132).age()).isPositive();
		assertThat(ColumnLayout.forWidth(40).age()).as("age goes first").isZero();
		assertThat(ColumnLayout.forWidth(40).name()).isPositive();

		ColumnLayout veryNarrow = ColumnLayout.forWidth(22);
		assertThat(veryNarrow.state()).as("state goes second").isZero();
		assertThat(veryNarrow.name()).isPositive();

		ColumnLayout tiny = ColumnLayout.forWidth(14);
		assertThat(tiny.namespace()).as("namespace goes last, before the name is touched").isZero();
		assertThat(tiny.name()).isPositive();
	}

	@Test
	void aWidthTooSmallForAnythingIsAllName() {
		ColumnLayout none = ColumnLayout.forWidth(4);

		assertThat(none.namespace()).isZero();
		assertThat(none.state()).isZero();
		assertThat(none.age()).isZero();
		assertThat(none.name()).isEqualTo(4);
	}

	@Test
	void aNegativeWidthIsTreatedAsZeroRatherThanThrowing() {
		assertThat(ColumnLayout.forWidth(-10).total()).isZero();
	}

	@Test
	void aClusterScopedKindGetsNoNamespaceColumnRatherThanAnEmptyOne() {
		ColumnLayout clusterScoped = ColumnLayout.forWidth(132, false);

		assertThat(clusterScoped.namespace()).isZero();
		assertThat(clusterScoped.name()).isGreaterThan(ColumnLayout.forWidth(132, true).name());
		assertThat(clusterScoped.total()).isLessThanOrEqualTo(132);
	}

	@Test
	void aKindsOwnColumnsGetRoomAndTheNamePaysForIt() {
		List<String> pod = List.of("Ready", "Restarts", "Node");

		ColumnLayout with = ColumnLayout.forWidth(132, true, pod);
		ColumnLayout without = ColumnLayout.forWidth(132, true, List.of());

		assertThat(with.extras()).hasSize(3).allSatisfy((width) -> assertThat(width).isPositive());
		assertThat(with.name()).isLessThan(without.name());
		assertThat(with.total()).isLessThanOrEqualTo(132);
	}

	/**
	 * Nodes carry fifteen. On a wide terminal they all fit; on a normal one they do not,
	 * and what has to survive is the name — a row you cannot identify is worse than a row
	 * whose architecture you cannot see.
	 */
	@Test
	void theKindsColumnsAreTheFirstToGoAndTheyGoFromTheRight() {
		List<String> nodes = List.of("Roles", "Taints", "Version", "Internal IP", "Schedulable", "Conditions",
				"External IP", "Pod Capacity", "Capacity", "Instance Type", "Zone", "OS Image", "Kernel",
				"Container Runtime", "Architecture");

		assertThat(ColumnLayout.forWidth(300, false, nodes).extras()).hasSize(15);
		assertThat(ColumnLayout.forWidth(132, false, nodes).extras()).hasSizeLessThan(15).isNotEmpty();
		assertThat(ColumnLayout.forWidth(60, false, nodes).extras()).hasSizeLessThan(5);
		assertThat(ColumnLayout.forWidth(40, false, nodes).extras()).isEmpty();
		assertThat(ColumnLayout.forWidth(40, false, nodes).age()).as("age survives longer than any kind column")
			.isPositive();
	}

	@Test
	void aLayoutWithKindColumnsStillNeverOverflows() {
		List<String> nodes = List.of("Roles", "Taints", "Version", "Internal IP", "Schedulable", "Conditions",
				"External IP", "Pod Capacity", "Capacity", "Instance Type", "Zone", "OS Image", "Kernel",
				"Container Runtime", "Architecture");
		for (int width = 1; width <= 300; width++) {
			assertThat(ColumnLayout.forWidth(width, true, nodes).total())
				.as("layout at width %d must fit in %d cells", width, width)
				.isLessThanOrEqualTo(width);
		}
	}

}
