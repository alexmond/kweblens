package org.alexmond.kweblens.cluster;

import java.util.Optional;

import io.fabric8.kubernetes.client.ConfigBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * The runtime add/edit/remove path. The point of most of these is the invariant that a
 * rejected definition changes nothing: the registry a user is browsing must not be
 * disturbed by someone pasting a broken kubeconfig into the editor.
 */
class ClusterConfigServiceTest {

	// RFC 5737 documentation addresses — never a real or lab endpoint, and nothing here
	// connects anyway (building a fabric8 client does not reach the network).
	private static final String KUBECONFIG = """
			apiVersion: v1
			kind: Config
			current-context: staging
			clusters:
			- name: staging-cluster
			  cluster:
			    server: https://198.51.100.10:6443
			- name: prod-cluster
			  cluster:
			    server: https://198.51.100.11:6443
			contexts:
			- name: staging
			  context:
			    cluster: staging-cluster
			    user: staging-user
			- name: prod
			  context:
			    cluster: prod-cluster
			    user: prod-user
			users:
			- name: staging-user
			  user:
			    token: s3cr3t-staging-token
			- name: prod-user
			  user:
			    token: s3cr3t-prod-token
			""";

	private final ClusterRegistry registry = new ClusterRegistry();

	private final InMemoryClusterStore store = new InMemoryClusterStore();

	private final ClusterConfigService service = new ClusterConfigService(this.registry, this.store);

	@AfterEach
	void closeRegistry() {
		this.registry.close();
	}

	private ClusterDefinition definition(String id, String context) {
		return new ClusterDefinition(id, "Staging", context, KUBECONFIG);
	}

	private KubernetesClient clientPointedAt(String masterUrl) {
		return new KubernetesClientBuilder().withConfig(new ConfigBuilder().withMasterUrl(masterUrl).build()).build();
	}

	@Test
	void addRegistersPersistsAndMarksTheClusterRuntimeManaged() {
		ClusterInfo info = this.service.add(definition("staging", "staging"));

		assertThat(info.id()).isEqualTo("staging");
		assertThat(info.origin()).isEqualTo(ClusterOrigin.RUNTIME);
		assertThat(info.masterUrl()).contains("198.51.100.10");
		assertThat(this.registry.client("staging")).isPresent();
		assertThat(this.store.find("staging")).get().extracting(ClusterDefinition::hasKubeconfig).isEqualTo(true);
	}

	@Test
	void addSelectsTheRequestedContextRatherThanTheCurrentOne() {
		ClusterInfo info = this.service.add(definition("prod", "prod"));

		assertThat(info.masterUrl()).contains("198.51.100.11");
	}

	@Test
	void aMalformedKubeconfigIsRejectedAndLeavesTheRegistryUntouched() {
		this.service.add(definition("staging", "staging"));

		assertThatThrownBy(() -> this.service.add(new ClusterDefinition("broken", "Broken", null, "}{ not yaml")))
			.isInstanceOf(InvalidClusterException.class);

		// The pre-existing cluster is still registered and still usable, and the rejected
		// one left nothing behind in either the registry or the store.
		assertThat(this.registry.list()).extracting(ClusterInfo::id).containsExactly("staging");
		assertThat(this.registry.client("staging")).isPresent();
		assertThat(this.store.load()).extracting(ClusterDefinition::id).containsExactly("staging");
	}

	@Test
	void aKubeconfigWithNoContextsIsRejected() {
		assertThatThrownBy(() -> this.service.add(new ClusterDefinition("empty", "Empty", null, "apiVersion: v1")))
			.isInstanceOf(InvalidClusterException.class)
			.hasMessageContaining("no contexts");
		assertThat(this.registry.list()).isEmpty();
	}

	@Test
	void aContextThatIsNotInTheFileIsRejectedAndNamesTheOnesThatAre() {
		assertThatThrownBy(() -> this.service.add(definition("staging", "does-not-exist")))
			.isInstanceOf(InvalidClusterException.class)
			.hasMessageContaining("staging, prod");
		assertThat(this.registry.list()).isEmpty();
	}

	@Test
	void anIdThatIsNotUrlOrFileSafeIsRejected() {
		assertThatThrownBy(() -> this.service.add(new ClusterDefinition("../escape", "Escape", "staging", KUBECONFIG)))
			.isInstanceOf(InvalidClusterException.class)
			.hasMessageContaining("Invalid cluster id");
		assertThat(this.store.load()).isEmpty();
	}

	@Test
	void addingAnIdThatIsAlreadyRegisteredConflicts() {
		this.service.add(definition("staging", "staging"));

		assertThatThrownBy(() -> this.service.add(definition("staging", "prod")))
			.isInstanceOf(ClusterConflictException.class);
	}

	@Test
	void removeClosesTheClientAndForgetsTheCredential() {
		// A mock rather than a real client: "was it closed" is the assertion, and a real
		// client would have to be probed over the network to tell the difference.
		KubernetesClient client = mock(KubernetesClient.class);
		this.registry.register("staging", "Staging", client, ClusterOrigin.RUNTIME);
		this.store.save(new ClusterDefinition("staging", "Staging", "staging", KUBECONFIG));

		this.service.remove("staging");

		assertThat(this.registry.client("staging")).isEmpty();
		assertThat(this.store.load()).isEmpty();
		verify(client).close();
	}

	@Test
	void aClusterDeclaredInConfigurationCannotBeEditedOrRemovedAtRuntime() {
		this.registry.register("declared", "Declared", clientPointedAt("https://198.51.100.20:6443/"));

		assertThatThrownBy(() -> this.service.remove("declared")).isInstanceOf(ClusterConflictException.class)
			.hasMessageContaining("declared in configuration");
		assertThatThrownBy(() -> this.service.update("declared", definition("declared", "prod")))
			.isInstanceOf(ClusterConflictException.class);
		assertThat(this.registry.client("declared")).isPresent();
	}

	@Test
	void removingAnUnknownClusterIsNotFound() {
		assertThatThrownBy(() -> this.service.remove("ghost")).isInstanceOf(UnknownClusterException.class);
	}

	@Test
	void updateKeepsTheStoredCredentialWhenNoneIsSupplied() {
		this.service.add(definition("staging", "staging"));

		ClusterInfo info = this.service.update("staging", new ClusterDefinition(null, "Renamed", null, null));

		assertThat(info.name()).isEqualTo("Renamed");
		assertThat(info.masterUrl()).contains("198.51.100.10");
		assertThat(this.store.find("staging")).get().extracting(ClusterDefinition::kubeconfig).isEqualTo(KUBECONFIG);
	}

	@Test
	void updateCanSwitchContext() {
		this.service.add(definition("staging", "staging"));

		ClusterInfo info = this.service.update("staging", new ClusterDefinition(null, null, "prod", null));

		assertThat(info.masterUrl()).contains("198.51.100.11");
		assertThat(this.registry.info("staging")).get()
			.extracting(ClusterInfo::origin)
			.isEqualTo(ClusterOrigin.RUNTIME);
	}

	@Test
	void describeReportsTheAvailableContextsButNeverTheCredential() {
		this.service.add(definition("staging", "staging"));

		ClusterConfigView view = this.service.describe("staging");

		assertThat(view.contexts()).containsExactly("staging", "prod");
		assertThat(view.kubeconfigStored()).isTrue();
		assertThat(view.origin()).isEqualTo(ClusterOrigin.RUNTIME);
		// The whole view is rendered to JSON, so nothing anywhere in it may carry the
		// token.
		assertThat(view.toString()).doesNotContain("s3cr3t");
	}

	@Test
	void describeOfAStaticClusterReportsNoStoredCredential() {
		this.registry.register("declared", "Declared", clientPointedAt("https://198.51.100.20:6443/"));

		ClusterConfigView view = this.service.describe("declared");

		assertThat(view.origin()).isEqualTo(ClusterOrigin.STATIC);
		assertThat(view.kubeconfigStored()).isFalse();
		assertThat(view.contexts()).isEmpty();
	}

	@Test
	void inspectListsContextsWithoutRegisteringAnything() {
		assertThat(this.service.inspect(KUBECONFIG)).containsExactly("staging", "prod");
		assertThat(this.registry.list()).isEmpty();
		assertThatThrownBy(() -> this.service.inspect("apiVersion: v1")).isInstanceOf(InvalidClusterException.class);
	}

	@Test
	void restoreRegistersPersistedClustersButNeverShadowsADeclaredOne() {
		this.store.save(new ClusterDefinition("staging", "Staging", "staging", KUBECONFIG));
		this.store.save(new ClusterDefinition("declared", "Persisted", "prod", KUBECONFIG));
		this.registry.register("declared", "Declared", clientPointedAt("https://198.51.100.20:6443/"));

		int restored = this.service.restore();

		assertThat(restored).isEqualTo(1);
		assertThat(this.registry.info("staging")).get()
			.extracting(ClusterInfo::origin)
			.isEqualTo(ClusterOrigin.RUNTIME);
		assertThat(this.registry.info("declared")).get().extracting(ClusterInfo::name).isEqualTo("Declared");
	}

	@Test
	void restoreSkipsAnUnusablePersistedDefinitionRatherThanFailingStartup() {
		this.store.save(new ClusterDefinition("broken", "Broken", null, "}{ not yaml"));
		this.store.save(new ClusterDefinition("staging", "Staging", "staging", KUBECONFIG));

		assertThat(this.service.restore()).isEqualTo(1);
		assertThat(this.registry.list()).extracting(ClusterInfo::id).containsExactly("staging");
	}

	@Test
	void anUnreadableStoreDoesNotStopStartup() {
		// The in-cluster shape of this is a missing RBAC grant on Secrets. kweblens must
		// still boot and still browse the clusters it was configured with; only runtime
		// clusters are unavailable.
		ClusterStore failing = mock(ClusterStore.class);
		given(failing.load()).willThrow(new IllegalStateException("secrets is forbidden"));
		given(failing.describe()).willReturn("Kubernetes Secrets kweblens/kweblens-cluster-*");

		assertThat(new ClusterConfigService(this.registry, failing).restore()).isZero();
	}

	@Test
	void aStoreThatRefusesTheWriteDoesNotStrandTheClient() {
		// The in-cluster shape of this is a missing RBAC grant on Secrets, or a read-only
		// data directory for the file store. Nothing but add() holds the client at that
		// point — the registry never took it — so if it is not closed here it is stranded
		// for the life of the process, and a fabric8 client is not merely an object: the
		// vertx factory gives each one its own Vertx instance and its event-loop threads.
		ClusterStore failing = mock(ClusterStore.class);
		willThrow(new IllegalStateException("secrets is forbidden")).given(failing).save(any());
		KubernetesClient client = mock(KubernetesClient.class);
		ClusterConfigService service = new StubbedClientService(this.registry, failing, client);

		assertThatThrownBy(() -> service.add(definition("staging", "staging")))
			.isInstanceOf(IllegalStateException.class);

		verify(client).close();
		assertThat(this.registry.list()).isEmpty();
	}

	@Test
	void aFailedEditStrandsNothingAndLeavesTheClusterUsable() {
		KubernetesClient existing = mock(KubernetesClient.class);
		this.registry.register("staging", "Staging", existing, ClusterOrigin.RUNTIME);
		ClusterStore failing = mock(ClusterStore.class);
		given(failing.find("staging"))
			.willReturn(Optional.of(new ClusterDefinition("staging", "Staging", "staging", KUBECONFIG)));
		willThrow(new IllegalStateException("secrets is forbidden")).given(failing).save(any());
		KubernetesClient replacement = mock(KubernetesClient.class);
		ClusterConfigService service = new StubbedClientService(this.registry, failing, replacement);

		assertThatThrownBy(() -> service.update("staging", new ClusterDefinition(null, "Renamed", null, null)))
			.isInstanceOf(IllegalStateException.class);

		// The client built for the edit is closed, and the one the operator is browsing
		// with is neither closed nor replaced.
		verify(replacement).close();
		verify(existing, never()).close();
		assertThat(this.registry.client("staging")).contains(existing);
	}

	@Test
	void theDefinitionNeverPrintsItsCredential() {
		ClusterDefinition withCredential = definition("staging", "staging");

		assertThat(withCredential.toString()).doesNotContain("s3cr3t").contains("<redacted>");
		assertThat(withCredential.withKubeconfig(null).toString()).contains("<none>");
	}

	/**
	 * Substitutes the client the service would build. "Was it closed?" is not a question
	 * a real fabric8 client answers without reaching the network, and the leak this
	 * covers is precisely a client nobody holds a reference to.
	 */
	private static final class StubbedClientService extends ClusterConfigService {

		private final KubernetesClient client;

		StubbedClientService(ClusterRegistry registry, ClusterStore store, KubernetesClient client) {
			super(registry, store);
			this.client = client;
		}

		@Override
		KubernetesClient clientFor(ClusterDefinition definition) {
			return this.client;
		}

	}

}
