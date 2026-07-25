package org.alexmond.kweblens.web.helm;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Parsing a Helm release's rendered manifest into the resource refs used to link a
 * release to the objects it manages.
 */
class HelmManifestParseTest {

	private static final String MANIFEST = """
			---
			# Source: chart/templates/sa.yaml
			apiVersion: v1
			kind: ServiceAccount
			metadata:
			  name: my-app
			---
			apiVersion: apps/v1
			kind: Deployment
			metadata:
			  name: my-app
			  namespace: explicit-ns
			spec:
			  replicas: 1
			---
			# a stray empty document and a doc with no kind are skipped
			apiVersion: v1
			metadata:
			  name: no-kind-here
			""";

	@Test
	void parsesEachDocumentAndDefaultsNamespaceToTheRelease() {
		List<HelmResourceRef> refs = HelmService.parseManifest(MANIFEST, "release-ns");

		assertThat(refs).hasSize(2);
		assertThat(refs).anySatisfy((r) -> {
			assertThat(r.kind()).isEqualTo("ServiceAccount");
			assertThat(r.name()).isEqualTo("my-app");
			assertThat(r.namespace()).isEqualTo("release-ns");
		});
		assertThat(refs).anySatisfy((r) -> {
			assertThat(r.kind()).isEqualTo("Deployment");
			assertThat(r.apiVersion()).isEqualTo("apps/v1");
			assertThat(r.namespace()).isEqualTo("explicit-ns");
		});
	}

	@Test
	void blankManifestYieldsNoRefs() {
		assertThat(HelmService.parseManifest("", "ns")).isEmpty();
		assertThat(HelmService.parseManifest(null, "ns")).isEmpty();
	}

}
