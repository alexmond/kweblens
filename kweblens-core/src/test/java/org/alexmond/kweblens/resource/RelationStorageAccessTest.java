package org.alexmond.kweblens.resource;

import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.PersistentVolumeBuilder;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaimBuilder;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.api.model.ServiceAccountBuilder;
import io.fabric8.kubernetes.api.model.rbac.ClusterRoleBindingBuilder;
import io.fabric8.kubernetes.api.model.rbac.RoleBindingBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import io.fabric8.kubernetes.client.utils.Serialization;
import org.junit.jupiter.api.Test;

import org.alexmond.kweblens.cluster.ClusterRegistry;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The storage and identity relations, plus the two rules every relation obeys — bounded
 * output, and nothing leaving that a table row has no business carrying.
 *
 * <p>
 * Deliberately NOT static, for the same reason as {@link RelationServiceTest}: a static
 * mock client shares one API server across the class, and the PVC join lists a whole
 * namespace, so seeded pods would leak between tests.
 */
@EnableKubernetesMockClient(crud = true)
class RelationStorageAccessTest {

	KubernetesClient client;

	private static final ResourceDescriptor PODS = WellKnownKinds.PODS;

	private static final ResourceDescriptor CLAIMS = ResourceDescriptor.coreNamespaced("persistentvolumeclaims",
			"Persistent Volume Claims", "PersistentVolumeClaim", "persistentvolumeclaims");

	private static final ResourceDescriptor VOLUMES = ResourceDescriptor.coreCluster("persistentvolumes",
			"Persistent Volumes", "PersistentVolume", "persistentvolumes");

	private static final ResourceDescriptor ACCOUNTS = ResourceDescriptor.coreNamespaced("serviceaccounts",
			"Service Accounts", "ServiceAccount", "serviceaccounts");

	private RelationService service() {
		ClusterRegistry registry = new ClusterRegistry();
		registry.register("mock", "mock", this.client);
		return new RelationService(registry);
	}

	/** Round-trip a typed object into the generic form the service actually receives. */
	private GenericKubernetesResource generic(Object typed) {
		return Serialization.unmarshal(Serialization.asJson(typed), GenericKubernetesResource.class);
	}

	@Test
	void findsThePodsHoldingAPersistentVolumeClaim() {
		// Why a Terminating claim will not go away, and why a ReadWriteOnce claim will
		// not
		// attach anywhere else.
		this.client.pods()
			.inNamespace("app")
			.resource(new PodBuilder().withNewMetadata()
				.withName("holder")
				.withNamespace("app")
				.endMetadata()
				.withNewSpec()
				.addNewVolume()
				.withName("data")
				.withNewPersistentVolumeClaim()
				.withClaimName("data")
				.endPersistentVolumeClaim()
				.endVolume()
				.addNewContainer()
				.withName("c")
				.endContainer()
				.endSpec()
				.build())
			.create();
		GenericKubernetesResource claim = generic(new PersistentVolumeClaimBuilder().withNewMetadata()
			.withName("data")
			.withNamespace("app")
			.endMetadata()
			.build());

		Relation holders = service().relationsFor("mock", CLAIMS, claim).get("mountedBy");

		assertThat(holders.items()).hasSize(1);
		assertThat(Serialization.asJson(holders.items().get(0))).contains("holder");
	}

	@Test
	void resolvesTheStorageBindingFromBothEnds() {
		this.client.persistentVolumes()
			.resource(new PersistentVolumeBuilder().withNewMetadata()
				.withName("pv-1")
				.endMetadata()
				.withNewSpec()
				.withNewClaimRef()
				.withNamespace("app")
				.withName("data")
				.endClaimRef()
				.endSpec()
				.build())
			.create();
		this.client.persistentVolumeClaims()
			.inNamespace("app")
			.resource(new PersistentVolumeClaimBuilder().withNewMetadata()
				.withName("data")
				.withNamespace("app")
				.endMetadata()
				.withNewSpec()
				.withVolumeName("pv-1")
				.endSpec()
				.build())
			.create();
		GenericKubernetesResource claim = generic(new PersistentVolumeClaimBuilder().withNewMetadata()
			.withName("data")
			.withNamespace("app")
			.endMetadata()
			.withNewSpec()
			.withVolumeName("pv-1")
			.endSpec()
			.build());
		GenericKubernetesResource volume = generic(new PersistentVolumeBuilder().withNewMetadata()
			.withName("pv-1")
			.endMetadata()
			.withNewSpec()
			.withNewClaimRef()
			.withNamespace("app")
			.withName("data")
			.endClaimRef()
			.endSpec()
			.build());

		Map<String, Relation> claimRelations = service().relationsFor("mock", CLAIMS, claim);
		// A cluster-scoped object still gets its relations — that used to be impossible.
		Map<String, Relation> volumeRelations = service().relationsFor("mock", VOLUMES, volume);

		assertThat(claimRelations.get("boundVolume").items()).hasSize(1);
		assertThat(volumeRelations.get("boundClaim").items()).hasSize(1);
		// spec.selector on a claim selects VOLUMES, so it must never be run against pods.
		assertThat(claimRelations).doesNotContainKey("selectedPods");
	}

	@Test
	void reportsAnUnboundClaimAsEmptyWithoutAskingTheApiServer() {
		GenericKubernetesResource claim = generic(new PersistentVolumeClaimBuilder().withNewMetadata()
			.withName("pending")
			.withNamespace("app")
			.endMetadata()
			.build());

		Relation bound = service().relationsFor("mock", CLAIMS, claim).get("boundVolume");

		assertThat(bound.items()).isEmpty();
		assertThat(bound.error()).isNull();
	}

	@Test
	void resolvesTheServiceAccountAPodRunsAsIncludingTheImplicitDefault() {
		this.client.serviceAccounts()
			.inNamespace("app")
			.resource(new ServiceAccountBuilder().withNewMetadata()
				.withName("default")
				.withNamespace("app")
				.endMetadata()
				.build())
			.create();
		GenericKubernetesResource pod = generic(
				new PodBuilder().withNewMetadata().withName("web-1").withNamespace("app").endMetadata().build());

		Relation account = service().relationsFor("mock", PODS, pod).get("serviceAccount");

		assertThat(account.items()).hasSize(1);
		assertThat(Serialization.asJson(account.items().get(0))).contains("default");
	}

	@Test
	void findsBothTheDirectAndTheGroupGrantsOnAServiceAccount() {
		this.client.rbac()
			.roleBindings()
			.inNamespace("app")
			.resource(new RoleBindingBuilder().withNewMetadata()
				.withName("direct")
				.withNamespace("app")
				.endMetadata()
				.addNewSubject()
				.withKind("ServiceAccount")
				.withName("runner")
				.endSubject()
				.withNewRoleRef()
				.withKind("Role")
				.withName("reader")
				.endRoleRef()
				.build())
			.create();
		this.client.rbac()
			.roleBindings()
			.inNamespace("app")
			.resource(new RoleBindingBuilder().withNewMetadata()
				.withName("someone-else")
				.withNamespace("app")
				.endMetadata()
				.addNewSubject()
				.withKind("ServiceAccount")
				.withName("other")
				.endSubject()
				.withNewRoleRef()
				.withKind("Role")
				.withName("reader")
				.endRoleRef()
				.build())
			.create();
		this.client.rbac()
			.clusterRoleBindings()
			.resource(new ClusterRoleBindingBuilder().withNewMetadata()
				.withName("by-group")
				.endMetadata()
				.addNewSubject()
				.withKind("Group")
				.withName("system:serviceaccounts:app")
				.endSubject()
				.withNewRoleRef()
				.withKind("ClusterRole")
				.withName("view")
				.endRoleRef()
				.build())
			.create();
		GenericKubernetesResource account = generic(new ServiceAccountBuilder().withNewMetadata()
			.withName("runner")
			.withNamespace("app")
			.endMetadata()
			.build());

		Relation grants = service().relationsFor("mock", ACCOUNTS, account).get("grantedBy");

		assertThat(grants.items()).hasSize(2);
		assertThat(Serialization.asJson(grants.items())).doesNotContain("someone-else");
	}

	@Test
	void dropsASecretsDataBeforeItCanLeaveAsARelationItem() {
		// No relation returns a Secret today. The guard is the point: adding one later
		// must
		// not quietly turn a table row into a credential disclosure. Dropped entirely
		// rather than emptied, so it cannot read as "the Secret has no keys".
		var secret = new SecretBuilder().withNewMetadata()
			.withName("db-creds")
			.withNamespace("app")
			.endMetadata()
			.withData(Map.of("password", "aHVudGVyMg=="))
			.build();

		String json = Serialization.asJson(RelationSupport.forDisplay(secret));

		assertThat(json).contains("db-creds").doesNotContain("aHVudGVyMg==").doesNotContain("password");
	}

	@Test
	void boundsAndReportsTruncationRatherThanImplyingCompleteness() {
		assertThat(RelationSupport.bound(List.of(1, 2, 3)).truncated()).isFalse();
		List<Integer> many = IntStream.rangeClosed(1, RelationSupport.MAX_ITEMS + 5).boxed().toList();
		Relation capped = RelationSupport.bound(many);
		assertThat(capped.items()).hasSize(RelationSupport.MAX_ITEMS);
		assertThat(capped.truncated()).isTrue();
	}

}
