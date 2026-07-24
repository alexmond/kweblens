package org.alexmond.kweblens.cluster;

import io.fabric8.kubernetes.client.KubernetesClient;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class KubeconfigLoaderTest {

	private static final String KUBECONFIG = """
			apiVersion: v1
			kind: Config
			current-context: dev
			clusters:
			- name: dev-cluster
			  cluster:
			    server: https://dev.example:6443
			- name: prod-cluster
			  cluster:
			    server: https://prod.example:6443
			contexts:
			- name: dev
			  context:
			    cluster: dev-cluster
			    user: dev-user
			- name: prod
			  context:
			    cluster: prod-cluster
			    user: prod-user
			users:
			- name: dev-user
			  user: {}
			- name: prod-user
			  user: {}
			""";

	@Test
	void enumeratesAllContexts() {
		assertThat(KubeconfigLoader.contexts(KUBECONFIG)).containsExactly("dev", "prod");
	}

	@Test
	void readsCurrentContext() {
		assertThat(KubeconfigLoader.currentContext(KUBECONFIG)).isEqualTo("dev");
	}

	@Test
	void buildsAClientPointedAtTheChosenContext() {
		try (KubernetesClient client = KubeconfigLoader.clientFor(KUBECONFIG, null, "prod")) {
			assertThat(String.valueOf(client.getMasterUrl())).contains("prod.example:6443");
		}
	}

	@Test
	void emptyOrBlankKubeconfigYieldsNoContexts() {
		assertThat(KubeconfigLoader.contexts("")).isEmpty();
		assertThat(KubeconfigLoader.currentContext(null)).isNull();
	}

}
