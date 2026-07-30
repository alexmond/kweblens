package org.alexmond.kweblens.web.mcp;

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.client.utils.Serialization;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What must not leave the server through a tool call.
 *
 * <p>
 * This is the security boundary of the whole tool surface, so the assertions are on the
 * serialised output rather than on fields: what matters is that the value cannot be found
 * anywhere in what is sent, not that one accessor was cleared. A model's tool output goes
 * over a network and into inference logs, and there is no recalling it.
 */
class ToolRedactionTest {

	private GenericKubernetesResource parse(String yaml) {
		return Serialization.unmarshal(yaml, GenericKubernetesResource.class);
	}

	private String output(GenericKubernetesResource resource) {
		return Serialization.asJson(ToolRedaction.forTool(resource));
	}

	@Test
	void removesSecretValuesButKeepsTheKeys() {
		// The keys are the useful part for diagnosis — a container failing with
		// CreateContainerConfigError because a key is missing is a real failure mode, and
		// saying so discloses nothing.
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
		assertThat(json).contains("password").contains("username").contains("db-credentials");
	}

	@Test
	void removesStringDataToo() {
		// stringData is the write-only convenience form; a Secret created that way and
		// read
		// back before the API server normalises it would leak in plain text.
		String json = output(parse("""
				apiVersion: v1
				kind: Secret
				metadata: {name: token, namespace: app}
				stringData:
				  api-key: plaintext-value-here
				"""));
		assertThat(json).doesNotContain("plaintext-value-here").contains("api-key");
	}

	@Test
	void removesTheLastAppliedConfigurationAnnotation() {
		// kubectl stores a VERBATIM copy of the applied object here, secret data
		// included.
		// Redacting the live fields while leaving this behind would be a leak that looks
		// like it was handled.
		String json = output(parse("""
				apiVersion: v1
				kind: Secret
				metadata:
				  name: token
				  namespace: app
				  annotations:
				    kubectl.kubernetes.io/last-applied-configuration: '{"data":{"api-key":"c3VwZXJzZWNyZXQ="}}'
				data:
				  api-key: c3VwZXJzZWNyZXQ=
				"""));
		assertThat(json).doesNotContain("c3VwZXJzZWNyZXQ=");
	}

	@Test
	void leavesANonSecretObjectsDataAlone() {
		// A ConfigMap's values are exactly what a diagnosis needs to read.
		String json = output(parse("""
				apiVersion: v1
				kind: ConfigMap
				metadata: {name: settings, namespace: app}
				data:
				  log-level: debug
				"""));
		assertThat(json).contains("log-level").contains("debug");
	}

	@Test
	void dropsManagedFields() {
		// Server-side bookkeeping no diagnosis needs, and routinely a third of the
		// payload
		// — which here is context-window budget spent on nothing.
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
	}

	@Test
	void toleratesAnObjectWithNoMetadataOrNoData() {
		assertThat(ToolRedaction.forTool(null)).isNull();
		assertThat(output(parse("""
				apiVersion: v1
				kind: Secret
				metadata: {name: empty, namespace: app}
				"""))).contains("empty");
	}

}
