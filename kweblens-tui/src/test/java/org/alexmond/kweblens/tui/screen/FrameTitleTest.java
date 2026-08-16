package org.alexmond.kweblens.tui.screen;

import org.junit.jupiter.api.Test;

import org.alexmond.kweblens.resource.WellKnownKinds;

import static org.assertj.core.api.Assertions.assertThat;

/** What the line above the table claims, in the shape k9s writes it. */
class FrameTitleTest {

	@Test
	void kindScopeAndCount() {
		assertThat(FrameTitle.of(View.of(WellKnownKinds.PODS, "kube-system"), 4)).isEqualTo("pods(kube-system)[4]");
	}

	@Test
	void everyNamespaceIsAScopeAndSaysSo() {
		assertThat(FrameTitle.of(View.of(WellKnownKinds.PODS, null), 7)).isEqualTo("pods(all)[7]");
	}

	@Test
	void aClusterScopedKindGetsNoParenthesesAtAll() {
		assertThat(FrameTitle.of(View.of(WellKnownKinds.NODES, null), 3)).as("nodes(all) would claim a narrowing")
			.isEqualTo("nodes[3]");
	}

	@Test
	void theActiveFilterIsInTheTitleBecauseARowCountWithoutItIsALie() {
		assertThat(FrameTitle.of(View.of(WellKnownKinds.PODS, null, "coredns"), 1))
			.isEqualTo("pods(all)[1] </coredns>");
	}

	@Test
	void aDrillDownReadsAsTheRelationshipItIs() {
		assertThat(FrameTitle.of(View.of(WellKnownKinds.PODS, "kube-system", "k8s-app=kube-dns"), 1))
			.isEqualTo("pods(kube-system)[1] </k8s-app=kube-dns>");
	}

}
