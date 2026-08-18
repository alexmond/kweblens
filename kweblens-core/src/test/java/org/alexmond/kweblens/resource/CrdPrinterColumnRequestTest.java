package org.alexmond.kweblens.resource;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer;
import io.fabric8.mockwebserver.http.RecordedRequest;
import org.junit.jupiter.api.Test;

import org.alexmond.kweblens.cluster.ClusterRegistry;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What {@link CrdService#printerColumns} puts on the wire — the request, not the outcome
 * (#459).
 *
 * <p>
 * <b>The outcome cannot tell these two implementations apart.</b> Listing every CRD in
 * the cluster and filtering in memory returns "no columns" for a built-in kind, and so
 * does asking for nothing at all; {@code CrdServiceTest} has asserted the former since
 * #367 and was green while the TUI stalled for 10 393 833 bytes on a keystroke. So this
 * counts requests and reads their paths, the same reason {@code ResourceCountTest}
 * asserts an exact query string rather than a count of seeded objects.
 *
 * <p>
 * The mock is deliberately <b>not</b> in crud mode: nothing is answered unless it is
 * stubbed here, so the collection path {@code …/customresourcedefinitions} is not merely
 * unused, it is unavailable — and it still shows up in the recorded requests if anything
 * asks for it.
 */
@EnableKubernetesMockClient
class CrdPrinterColumnRequestTest {

	KubernetesClient client;

	KubernetesMockServer server;

	private static final String BY_NAME = "/apis/apiextensions.k8s.io/v1/customresourcedefinitions/";

	private static final String CERTIFICATES = """
			{"apiVersion":"apiextensions.k8s.io/v1","kind":"CustomResourceDefinition",
			 "metadata":{"name":"certificates.cert-manager.io"},
			 "spec":{"group":"cert-manager.io","scope":"Namespaced",
			  "names":{"plural":"certificates","singular":"certificate","kind":"Certificate"},
			  "versions":[{"name":"v1","served":true,"storage":true,
			   "additionalPrinterColumns":[
			    {"name":"Ready","jsonPath":".status.conditions[0].status","type":"string"},
			    {"name":"Wide","jsonPath":".spec.secretName","type":"string","priority":1}]}]}}
			""";

	private CrdService service() {
		ClusterRegistry registry = new ClusterRegistry();
		registry.register("mock", "mock", this.client);
		return new CrdService(registry);
	}

	/** Every path the mock server was asked for, in order, drained. */
	private List<String> requestedPaths() throws InterruptedException {
		List<String> paths = new ArrayList<>();
		for (int i = this.server.getRequestCount(); i > 0; i--) {
			RecordedRequest request = this.server.takeRequest(1, TimeUnit.SECONDS);
			paths.add((request != null) ? request.getPath() : "<none>");
		}
		return paths;
	}

	@Test
	void aCoreGroupKindIssuesNoCrdRequestAtAll() throws InterruptedException {
		// `:secret`, `:cm`, `:pvc`, `:sa` — and every built-in id NavCatalog spells,
		// because those are bare plurals too. A CRD's spec.group is required, so a kind
		// with no group is one no CRD can ever deliver, and that is knowable here.
		assertThat(service().printerColumns("mock", "secrets")).isEmpty();

		assertThat(requestedPaths()).as("a kind that cannot be CRD-delivered must not ask the cluster anything")
			.isEmpty();
	}

	@Test
	void aBuiltInInAnApiGroupAsksForItsOwnCrdByNameAndNothingElse() throws InterruptedException {
		// `:sts` — discovery ids this `apps.statefulsets`, which is indistinguishable
		// from a CRD id without asking. One keyed GET is the whole cost, and its 404 is
		// the quiet "no declared columns" the fallback owes a built-in.
		assertThat(service().printerColumns("mock", "apps.statefulsets")).isEmpty();

		assertThat(requestedPaths()).containsExactly(BY_NAME + "statefulsets.apps");
	}

	@Test
	void aRealCrdsPrinterColumnsArriveFromOneKeyedGet() throws InterruptedException {
		this.server.expect()
			.get()
			.withPath(BY_NAME + "certificates.cert-manager.io")
			.andReturn(200, CERTIFICATES)
			.once();

		assertThat(service().printerColumns("mock", "cert-manager.io.certificates"))
			.as("the feature the fallback exists for: a CRD's own additionalPrinterColumns, wide ones left out")
			.singleElement()
			.satisfies((column) -> assertThat(column.name()).isEqualTo("Ready"));

		assertThat(requestedPaths()).containsExactly(BY_NAME + "certificates.cert-manager.io");
	}

}
