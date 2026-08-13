package org.alexmond.kweblens.web.api;

import java.util.List;

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.client.utils.Serialization;
import org.junit.jupiter.api.Test;

import org.alexmond.kweblens.health.StatusContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a list payload may and may not carry.
 *
 * <p>
 * The assertions are on the serialised JSON rather than on fields, because the thing
 * being claimed is about the bytes on the wire: a value that is cleared on the object but
 * still appears in the output would be a saving that does not exist. In particular the
 * "keys survive" assertions pin a Jackson behaviour that is easy to lose — a mapper
 * configured to omit nulls would drop the whole key, and the Keys column would silently
 * start reporting 0.
 */
class ListProjectionTest {

	private GenericKubernetesResource parse(String yaml) {
		return Serialization.unmarshal(yaml, GenericKubernetesResource.class);
	}

	private String output(GenericKubernetesResource resource) {
		return Serialization.asJson(ListProjection.forList(resource, StatusContext.none()));
	}

	@Test
	void dropsManagedFieldsFromEveryKind() {
		String json = output(parse("""
				apiVersion: v1
				kind: Pod
				metadata:
				  name: web
				  namespace: app
				  managedFields:
				  - manager: kubectl-client-side-apply
				    operation: Update
				"""));
		assertThat(json).doesNotContain("managedFields").doesNotContain("kubectl-client-side-apply");
		assertThat(json).contains("web");
	}

	@Test
	void dropsSecretValuesButKeepsTheKeysSoTheCountSurvives() {
		// columns.ts renders `keys` as Object.keys(data).length. Dropping the map would
		// turn every Secret's Keys cell into 0 — a wrong number, which is worse than a
		// big payload.
		String json = output(parse("""
				apiVersion: v1
				kind: Secret
				metadata: {name: db-credentials, namespace: app}
				type: Opaque
				data:
				  password: c3VwZXJzZWNyZXQ=
				  username: YWRtaW4=
				"""));
		assertThat(json).doesNotContain("c3VwZXJzZWNyZXQ=").doesNotContain("YWRtaW4=");
		assertThat(json).contains("password").contains("username");
		assertThat(keysOf(json, "data")).containsExactly("password", "username");
	}

	@Test
	void dropsStringDataAndBinaryDataValuesToo() {
		String json = output(parse("""
				apiVersion: v1
				kind: Secret
				metadata: {name: token, namespace: app}
				stringData:
				  api-key: plaintext-value-here
				binaryData:
				  blob: AAECAw==
				"""));
		assertThat(json).doesNotContain("plaintext-value-here").doesNotContain("AAECAw==");
		assertThat(json).contains("api-key").contains("blob");
	}

	@Test
	void dropsConfigMapValues() {
		String json = output(parse("""
				apiVersion: v1
				kind: ConfigMap
				metadata: {name: settings, namespace: app}
				data:
				  log-level: debug
				"""));
		assertThat(json).doesNotContain("debug").contains("log-level");
	}

	@Test
	void leavesACustomResourcesOwnDataAlone() {
		// On a CRD `data` can be the whole spec, and a printer column is free to render
		// it. The strip is restricted to the two kinds whose list shows a key count.
		String json = output(parse("""
				apiVersion: example.com/v1
				kind: Widget
				metadata: {name: w1, namespace: app}
				data:
				  colour: green
				"""));
		assertThat(json).contains("colour").contains("green");
	}

	@Test
	void projectsEveryObjectOfAList() {
		List<GenericKubernetesResource> projected = ListProjection.forList(List.of(parse("""
				apiVersion: v1
				kind: Secret
				metadata: {name: a, namespace: app}
				data: {k: dmFsdWVB}
				"""), parse("""
				apiVersion: v1
				kind: Secret
				metadata: {name: b, namespace: app}
				data: {k: dmFsdWVC}
				""")), StatusContext.none());
		String json = Serialization.asJson(projected);
		assertThat(json).doesNotContain("dmFsdWVB").doesNotContain("dmFsdWVC");
		assertThat(json).contains("\"a\"").contains("\"b\"");
	}

	@Test
	void addsTheStateAnOverviewCardWouldCountThisObjectUnder() {
		// The row half of GH#337: the browser filters on a value the server computed, so
		// it has to be ON the row. And it is not status.phase — the phase here is
		// "Pending", which names nothing to act on, while "ImagePullBackOff" names the
		// fix.
		String json = output(parse("""
				apiVersion: v1
				kind: Pod
				metadata: {name: web, namespace: app}
				status:
				  phase: Pending
				  containerStatuses:
				  - name: c
				    state:
				      waiting: {reason: ImagePullBackOff}
				"""));
		assertThat(json).contains("\"kweblensState\"")
			.contains("\"label\":\"ImagePullBackOff\"")
			.contains("\"tone\":\"err\"");
	}

	@Test
	void addsNoStateToAKindNoRuleJudges() {
		// Absent, not "ok" and not an empty label: a row nobody examined must not be
		// selectable as healthy. The equality this field exists for is only worth
		// anything if silence stays silence.
		assertThat(output(parse("""
				apiVersion: example.com/v1
				kind: Widget
				metadata: {name: w1, namespace: app}
				"""))).doesNotContain("kweblensState");
	}

	@Test
	void addsNoStateToAContextCarryingKindWhenNoContextWasOpened() {
		// A ConfigMap DOES have a state — "Referenced" or not — but only a
		// StatusContext can reach it, and `output` here deliberately passes none.
		// Falling through to a bare "OK" is the one thing that must not happen: it
		// would make a row selectable on the strength of a scan nobody ran (GH#340).
		assertThat(output(parse("""
				apiVersion: v1
				kind: ConfigMap
				metadata: {name: settings, namespace: app}
				data: {log-level: debug}
				"""))).doesNotContain("kweblensState");
		assertThat(output(parse("""
				apiVersion: v1
				kind: Service
				metadata: {name: web, namespace: app}
				spec: {type: ClusterIP}
				"""))).doesNotContain("kweblensState");
	}

	@Test
	void toleratesNullsAndObjectsWithNothingToStrip() {
		assertThat(ListProjection.forList((GenericKubernetesResource) null, StatusContext.none())).isNull();
		assertThat(ListProjection.forList((List<GenericKubernetesResource>) null, StatusContext.none())).isEmpty();
		assertThat(output(parse("""
				apiVersion: v1
				kind: Secret
				metadata: {name: empty, namespace: app}
				"""))).contains("empty");
	}

	@SuppressWarnings("unchecked")
	private List<String> keysOf(String json, String field) {
		var map = Serialization.unmarshal(json, java.util.Map.class);
		return List.copyOf(((java.util.Map<String, Object>) map.get(field)).keySet());
	}

}
