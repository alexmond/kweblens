package org.alexmond.kweblens.resource;

import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The written label for a CRD kind (#433).
 *
 * <p>
 * <b>Every case below is a real CRD</b>, read off {@code kubectl get crd -o
 * jsonpath='{.spec.names.kind}{.spec.names.plural}'} on the cluster the ticket was filed
 * from — Gateway API, cert-manager, MetalLB, Traefik, VictoriaMetrics, k3s, VPA. They
 * were not invented to make the algorithm look good; they are what it will actually meet,
 * and the awkward ones ({@code VLogs}, {@code ServiceL2Status}, {@code BackendTLSPolicy})
 * came from that list rather than from imagination.
 *
 * <p>
 * <b>{@link #theNaiveImplementationGetsThemWrong} is the control.</b> "Split on capitals
 * and add an s" is the obvious implementation and it is wrong on most of this table; the
 * test asserts that it disagrees with every expectation it should disagree with, so the
 * cases are demonstrably load-bearing rather than a list the current code happens to
 * pass. Delete the acronym rule or the declared-plural lookup and this file goes red.
 */
class KindLabelTest {

	private static final List<Case> CASES = List.of(
			new Case("HTTPRoute", "httproutes", "HTTP Routes", "an acronym run keeps its letters together"),
			new Case("GRPCRoute", "grpcroutes", "GRPC Routes", "the same, one letter shorter"),
			new Case("TLSRoute", "tlsroutes", "TLS Routes", "and shorter again"),
			new Case("GatewayClass", "gatewayclasses", "Gateway Classes", "the plural is -es, and the CRD says so"),
			new Case("Gateway", "gateways", "Gateways", "the plain case, so the easy path is covered too"),
			new Case("BackendTLSPolicy", "backendtlspolicies", "Backend TLS Policies",
					"an acronym in the middle AND y -> ies"),
			new Case("Community", "communities", "Communities", "y -> ies with nothing else going on"),
			new Case("AccessControlPolicy", "accesscontrolpolicies", "Access Control Policies",
					"y -> ies, three words"),
			new Case("ServiceL2Status", "servicel2statuses", "Service L2 Statuses", "a digit is word body; -es"),
			new Case("ServiceBGPStatus", "servicebgpstatuses", "Service BGP Statuses", "an acronym then -es"),
			new Case("L2Advertisement", "l2advertisements", "L2 Advertisements",
					"a kind that starts with a digit word"),
			new Case("VLogs", "vlogs", "VLogs", "already plural, and a two-letter acronym must not become 'V Logs'"),
			new Case("VLAgent", "vlagents", "VL Agents", "the same two letters, where the run DOES leave a word"),
			new Case("API", "apis", "APIs", "an all-caps kind is one word"),
			new Case("APIPortalAuth", "apiportalauths", "API Portal Auths", "acronym first, two words after"),
			new Case("IPAddressPool", "ipaddresspools", "IP Address Pools", "acronym first, MetalLB's"),
			new Case("IngressRouteTCP", "ingressroutetcps", "Ingress Route TCPs", "an acronym LAST, pluralised"),
			new Case("ETCDSnapshotFile", "etcdsnapshotfiles", "ETCD Snapshot Files", "four-letter acronym first"),
			new Case("DNSEndpoint", "dnsendpoints", "DNS Endpoints", "three-letter acronym first"),
			new Case("VerticalPodAutoscaler", "verticalpodautoscalers", "Vertical Pod Autoscalers",
					"the kind from the ticket"),
			new Case("VerticalPodAutoscalerCheckpoint", "verticalpodautoscalercheckpoints",
					"Vertical Pod Autoscaler Checkpoints", "its sibling, which stays under Custom Resources"),
			new Case("HelmChartConfig", "helmchartconfigs", "Helm Chart Configs", "three plain words"),
			new Case("CertificateRequest", "certificaterequests", "Certificate Requests", "two plain words"));

	@Test
	void labelsEveryRealCrdKindTheWayTheClusterSpellsIt() {
		for (Case c : CASES) {
			assertThat(KindLabel.forCustomResource(c.kind(), c.plural())).as("%s (%s)", c.kind(), c.why())
				.isEqualTo(c.label());
		}
	}

	/**
	 * The control: the implementation anyone would write first, and the cases that prove
	 * it is not good enough. It is here rather than in a comment because a listed case
	 * whose naive answer is already correct is a case that is not testing anything, and
	 * only a run can tell those apart.
	 */
	@Test
	void theNaiveImplementationGetsThemWrong() {
		List<String> agreed = CASES.stream().filter((c) -> naive(c.kind()).equals(c.label())).map(Case::kind).toList();
		// These six are the kinds a naive split happens to get right: no acronym, no
		// irregular plural. They are in the table to cover the easy path, not to
		// discriminate. Every OTHER case must differ from the naive answer, or it is not
		// pulling its weight — and 17 of the 23 do.
		assertThat(agreed).containsExactlyInAnyOrder("Gateway", "HelmChartConfig", "CertificateRequest",
				"L2Advertisement", "VerticalPodAutoscaler", "VerticalPodAutoscalerCheckpoint");
	}

	/** Split before every capital, add an `s`. */
	private static String naive(String kind) {
		return kind.replaceAll("(?<!^)([A-Z])", " $1") + "s";
	}

	@Test
	void leavesTheKindAloneWhenTheDeclaredPluralIsNotItsPlural() {
		// A CRD may declare anything; a plural that shares no prefix with the kind cannot
		// say how the kind inflects, so nothing is invented and the singular stands.
		assertThat(KindLabel.forCustomResource("HTTPRoute", "somethingelse")).isEqualTo("HTTP Route");
		assertThat(KindLabel.forCustomResource("Gateway", null)).isEqualTo("Gateway");
		assertThat(KindLabel.forCustomResource("Gateway", "  ")).isEqualTo("Gateway");
	}

	@Test
	void survivesAMissingOrEmptyKind() {
		assertThat(KindLabel.forCustomResource(null, "things")).isNull();
		assertThat(KindLabel.forCustomResource("", "things")).isEmpty();
		assertThat(KindLabel.forCustomResource("X", "xs")).isEqualTo("Xs");
	}

	/**
	 * The invariant that says this is a re-spelling and not an invention: take the spaces
	 * out of the label, lower-case it, and you have the resource plural the API server
	 * serves at {@code /apis/<group>/<version>/<plural>}. No letter is added, dropped or
	 * changed — only the word boundaries the wire format cannot carry are put back.
	 */
	@Test
	void isTheClustersOwnPluralWithItsWordBoundariesPutBack() {
		for (Case c : CASES) {
			String squashed = KindLabel.forCustomResource(c.kind(), c.plural()).replace(" ", "");
			assertThat(squashed.toLowerCase(Locale.ROOT)).as("%s", c.kind()).isEqualTo(c.plural());
		}
	}

	/** One CRD as its two declared names, and what the rail should read. */
	private record Case(String kind, String plural, String label, String why) {

	}

}
