package org.alexmond.kweblens.tui.data;

import java.util.List;

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.apiextensions.v1.CustomResourceDefinition;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import io.fabric8.kubernetes.client.utils.Serialization;
import org.junit.jupiter.api.Test;

import org.alexmond.kweblens.column.Column;
import org.alexmond.kweblens.resource.ResourceDescriptor;
import org.alexmond.kweblens.resource.WellKnownKinds;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Where a kind's columns come from, through the real adapter.
 *
 * <p>
 * The point of this one is the second half: <b>a CRD's own
 * {@code additionalPrinterColumns} reach the terminal with no code change</b>. The CRD is
 * installed in the in-JVM API server, nothing in this build has ever heard of it, and its
 * declared columns come back evaluated.
 */
@EnableKubernetesMockClient(crud = true)
class CoreClusterDataSourceColumnsTest {

	KubernetesClient client;

	private static final String CRD = """
			apiVersion: apiextensions.k8s.io/v1
			kind: CustomResourceDefinition
			metadata:
			  name: helmcharts.helm.cattle.io
			spec:
			  group: helm.cattle.io
			  scope: Namespaced
			  names:
			    plural: helmcharts
			    singular: helmchart
			    kind: HelmChart
			  versions:
			  - name: v1
			    served: true
			    storage: true
			    additionalPrinterColumns:
			    - name: Chart
			      jsonPath: .spec.chart
			      type: string
			    - name: Version
			      jsonPath: .spec.version
			      type: string
			    - name: Wide
			      jsonPath: .spec.repo
			      type: string
			      priority: 1
			""";

	private static final ResourceDescriptor HELM_CHARTS = new ResourceDescriptor("helm.cattle.io.helmcharts",
			"HelmChart", "HelmChart", "helm.cattle.io", "v1", "helmcharts", true, false);

	private CoreClusterDataSource source() {
		return CoreStack.dataSource(this.client);
	}

	private static ResourceQuery query(ResourceDescriptor descriptor) {
		return new ResourceQuery(CoreStack.CLUSTER, descriptor, "kube-system");
	}

	@Test
	void aCoveredBuiltInAnswersFromTheCatalogWithoutAskingTheCluster() {
		assertThat(source().columns(query(WellKnownKinds.PODS)).stream().map(Column::key)).containsExactly("ready",
				"restarts", "node");
	}

	@Test
	void aCustomKindsDeclaredColumnsAreEvaluatedWithNoCodeChange() {
		this.client.resource(Serialization.unmarshal(CRD, CustomResourceDefinition.class)).create();
		GenericKubernetesResource chart = Serialization.unmarshal("""
				apiVersion: helm.cattle.io/v1
				kind: HelmChart
				metadata:
				  name: traefik
				  namespace: kube-system
				spec:
				  chart: traefik
				""", GenericKubernetesResource.class);

		List<Column> columns = source().columns(query(HELM_CHARTS));

		assertThat(columns).extracting(Column::header)
			.as("a wide column is left out, exactly as kubectl leaves it out")
			.containsExactly("Chart", "Version");
		assertThat(columns.stream().map((column) -> column.render(chart))).containsExactly("traefik", "—");
	}

	@Test
	void aKindWithNoCrdAndNoCatalogEntryHasNoColumnsAtAll() {
		assertThat(source().columns(query(WellKnownKinds.SECRETS))).isEmpty();
	}

}
