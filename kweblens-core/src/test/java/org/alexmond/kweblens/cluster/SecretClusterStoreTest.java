package org.alexmond.kweblens.cluster;

import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The in-cluster persistence backend, against the in-JVM API server double.
 */
@EnableKubernetesMockClient(crud = true)
class SecretClusterStoreTest {

	private static final String KUBECONFIG = """
			apiVersion: v1
			kind: Config
			current-context: dev
			clusters:
			- name: dev-cluster
			  cluster:
			    server: https://198.51.100.10:6443
			contexts:
			- name: dev
			  context:
			    cluster: dev-cluster
			    user: dev-user
			users:
			- name: dev-user
			  user:
			    token: s3cr3t-token
			""";

	// Non-static: each test gets a fresh CRUD server so they cannot see each other's
	// Secrets.
	KubernetesClient client;

	private SecretClusterStore store() {
		return new SecretClusterStore(this.client, "kweblens", "kweblens-cluster-");
	}

	@Test
	void roundTripsADefinitionThroughASecret() {
		SecretClusterStore store = store();

		store.save(new ClusterDefinition("dev", "Development", "dev", KUBECONFIG));

		assertThat(store.load()).singleElement()
			.satisfies((d) -> assertThat(d.id()).isEqualTo("dev"))
			.satisfies((d) -> assertThat(d.name()).isEqualTo("Development"))
			.satisfies((d) -> assertThat(d.context()).isEqualTo("dev"))
			.satisfies((d) -> assertThat(d.kubeconfig()).isEqualTo(KUBECONFIG));
		assertThat(this.client.secrets().inNamespace("kweblens").withName("kweblens-cluster-dev").get()).isNotNull();
	}

	@Test
	void savingTwiceUpdatesRatherThanFailing() {
		SecretClusterStore store = store();
		store.save(new ClusterDefinition("dev", "Development", "dev", KUBECONFIG));

		store.save(new ClusterDefinition("dev", "Renamed", null, KUBECONFIG));

		assertThat(store.load()).singleElement().extracting(ClusterDefinition::name).isEqualTo("Renamed");
		assertThat(store.load()).singleElement().extracting(ClusterDefinition::context).isNull();
	}

	@Test
	void deleteRemovesTheSecret() {
		SecretClusterStore store = store();
		store.save(new ClusterDefinition("dev", "Development", "dev", KUBECONFIG));

		store.delete("dev");

		assertThat(store.load()).isEmpty();
	}

	@Test
	void onlyKweblensManagedSecretsAreListed() {
		SecretClusterStore store = store();
		store.save(new ClusterDefinition("dev", "Development", "dev", KUBECONFIG));
		this.client.secrets()
			.inNamespace("kweblens")
			.resource(new SecretBuilder().withNewMetadata()
				.withName("someone-elses-secret")
				.withNamespace("kweblens")
				.endMetadata()
				.build())
			.create();

		assertThat(store.load()).extracting(ClusterDefinition::id).containsExactly("dev");
	}

	@Test
	void describeNamesTheLocationAndNotTheContents() {
		assertThat(store().describe()).isEqualTo("Kubernetes Secrets kweblens/kweblens-cluster-*");
		assertThat(store().persistent()).isTrue();
	}

}
