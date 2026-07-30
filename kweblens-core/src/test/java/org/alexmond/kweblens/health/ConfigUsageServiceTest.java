package org.alexmond.kweblens.health;

import java.util.List;

import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.api.model.ServiceAccountBuilder;
import io.fabric8.kubernetes.api.model.networking.v1.IngressBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import org.junit.jupiter.api.Test;

import org.alexmond.kweblens.cluster.ClusterRegistry;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * "Nothing references this" — a claim that is only as good as the search behind it.
 *
 * <p>
 * Most of these tests exist to stop a FALSE positive, because that is the expensive
 * direction here: a Secret wrongly listed as unreferenced is an invitation to delete
 * something load-bearing. Each covers one way an object is used that a naive
 * volume-mounts-only scan would miss.
 *
 * <p>
 * Deliberately NOT static: the reverse scan lists whole namespaces, so a shared API
 * server would make these order-dependent.
 */
@EnableKubernetesMockClient(crud = true)
class ConfigUsageServiceTest {

	KubernetesClient client;

	private ConfigUsageService service() {
		ClusterRegistry registry = new ClusterRegistry();
		registry.register("mock", "mock", this.client);
		return new ConfigUsageService(registry);
	}

	private void configMap(String name) {
		this.client.configMaps()
			.inNamespace("app")
			.resource(
					new ConfigMapBuilder().withNewMetadata().withName(name).withNamespace("app").endMetadata().build())
			.create();
	}

	private void secret(String name, String type) {
		this.client.secrets()
			.inNamespace("app")
			.resource(new SecretBuilder().withNewMetadata()
				.withName(name)
				.withNamespace("app")
				.endMetadata()
				.withType(type)
				.build())
			.create();
	}

	private PodBuilder pod(String name) {
		return new PodBuilder().withNewMetadata().withName(name).withNamespace("app").endMetadata();
	}

	private KindHealth configMaps() {
		return service().summarise("mock", "app").get(0);
	}

	private KindHealth secrets() {
		return service().summarise("mock", "app").get(1);
	}

	@Test
	void listsAConfigMapNothingMentions() {
		configMap("leftover");
		KindHealth health = configMaps();
		assertThat(health.total()).isEqualTo(1);
		assertThat(health.needsAttention()).singleElement().satisfies((item) -> {
			assertThat(item.name()).isEqualTo("leftover");
			assertThat(item.reason()).isEqualTo("not referenced in this namespace");
		});
	}

	@Test
	void seesAConfigMapMountedAsAVolume() {
		configMap("settings");
		this.client.pods()
			.inNamespace("app")
			.resource(pod("web").withNewSpec()
				.addNewVolume()
				.withName("cfg")
				.withNewConfigMap()
				.withName("settings")
				.endConfigMap()
				.endVolume()
				.endSpec()
				.build())
			.create();
		assertThat(configMaps().attention()).isZero();
	}

	@Test
	void seesAConfigMapReachedThroughEnvFrom() {
		configMap("env-bundle");
		this.client.pods()
			.inNamespace("app")
			.resource(pod("web").withNewSpec()
				.addNewContainer()
				.withName("c")
				.addNewEnvFrom()
				.withNewConfigMapRef()
				.withName("env-bundle")
				.endConfigMapRef()
				.endEnvFrom()
				.endContainer()
				.endSpec()
				.build())
			.create();
		assertThat(configMaps().attention()).isZero();
	}

	@Test
	void seesAConfigMapInsideAProjectedVolume() {
		// This is how every pod references the cluster's kube-root-ca.crt; missing it
		// would put that ConfigMap on the unused list in every single namespace.
		configMap("kube-root-ca.crt");
		this.client.pods()
			.inNamespace("app")
			.resource(pod("web").withNewSpec()
				.addNewVolume()
				.withName("token")
				.withNewProjected()
				.addNewSource()
				.withNewConfigMap()
				.withName("kube-root-ca.crt")
				.endConfigMap()
				.endSource()
				.endProjected()
				.endVolume()
				.endSpec()
				.build())
			.create();
		assertThat(configMaps().attention()).isZero();
	}

	@Test
	void seesASecretUsedOnlyToPullAnImage() {
		// A pull secret is mounted by nothing. Deleting it breaks every deploy from that
		// registry.
		secret("regcred", "kubernetes.io/dockerconfigjson");
		this.client.pods()
			.inNamespace("app")
			.resource(pod("web").withNewSpec()
				.addNewImagePullSecret()
				.withName("regcred")
				.endImagePullSecret()
				.endSpec()
				.build())
			.create();
		assertThat(secrets().attention()).isZero();
	}

	@Test
	void seesASecretHeldOnlyByAServiceAccount() {
		secret("sa-pull", "kubernetes.io/dockerconfigjson");
		this.client.serviceAccounts()
			.inNamespace("app")
			.resource(new ServiceAccountBuilder().withNewMetadata()
				.withName("builder")
				.withNamespace("app")
				.endMetadata()
				.addNewImagePullSecret()
				.withName("sa-pull")
				.endImagePullSecret()
				.build())
			.create();
		assertThat(secrets().attention()).isZero();
	}

	@Test
	void seesATlsSecretReferencedOnlyByAnIngress() {
		// Nothing mounts a TLS secret — the ingress controller reads it. This is the most
		// likely false positive in a real namespace.
		secret("web-tls", "kubernetes.io/tls");
		this.client.network()
			.v1()
			.ingresses()
			.inNamespace("app")
			.resource(new IngressBuilder().withNewMetadata()
				.withName("web")
				.withNamespace("app")
				.endMetadata()
				.withNewSpec()
				.addNewTl()
				.withSecretName("web-tls")
				.withHosts(List.of("example.test"))
				.endTl()
				.endSpec()
				.build())
			.create();
		assertThat(secrets().attention()).isZero();
	}

	@Test
	void excludesSecretsTheClusterOwnsRatherThanCallingThemUnused() {
		// A Helm release record looks unused by every structural measure and is the
		// opposite of disposable; a service-account token is created and owned by the
		// cluster.
		secret("sh.helm.release.v1.app.v3", "helm.sh/release.v1");
		secret("default-token-abcde", "kubernetes.io/service-account-token");
		KindHealth health = secrets();
		assertThat(health.total()).isEqualTo(2);
		assertThat(health.attention()).isZero();
	}

}
