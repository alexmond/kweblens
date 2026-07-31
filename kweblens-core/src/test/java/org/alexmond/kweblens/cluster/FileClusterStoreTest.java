package org.alexmond.kweblens.cluster;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileClusterStoreTest {

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

	@TempDir
	Path dir;

	@Test
	void roundTripsADefinition() {
		FileClusterStore store = new FileClusterStore(this.dir.resolve("clusters"));

		store.save(new ClusterDefinition("dev", "Development", "dev", KUBECONFIG));

		assertThat(store.load()).singleElement()
			.satisfies((d) -> assertThat(d.id()).isEqualTo("dev"))
			.satisfies((d) -> assertThat(d.name()).isEqualTo("Development"))
			.satisfies((d) -> assertThat(d.context()).isEqualTo("dev"))
			.satisfies((d) -> assertThat(d.kubeconfig()).isEqualTo(KUBECONFIG));
		assertThat(store.find("dev")).isPresent();
		assertThat(store.persistent()).isTrue();
		assertThat(store.describe()).contains("clusters");
	}

	@Test
	void anAbsentDirectoryIsAnEmptyStoreRatherThanAnError() {
		assertThat(new FileClusterStore(this.dir.resolve("never-created")).load()).isEmpty();
	}

	@Test
	void theCredentialIsWrittenOwnerOnly() throws IOException {
		Path clusters = this.dir.resolve("clusters");
		FileClusterStore store = new FileClusterStore(clusters);

		store.save(new ClusterDefinition("dev", "Development", null, KUBECONFIG));

		Path credential = clusters.resolve("dev.kubeconfig");
		assertThat(Files.readString(credential)).contains("s3cr3t-token");
		// The point of the store: a kubeconfig on disk must not be readable by other
		// users of the host.
		assertThat(Files.getPosixFilePermissions(credential))
			.isEqualTo(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
	}

	@Test
	void saveOverwritesAndDeleteRemovesBothFiles() {
		Path clusters = this.dir.resolve("clusters");
		FileClusterStore store = new FileClusterStore(clusters);
		store.save(new ClusterDefinition("dev", "Development", "dev", KUBECONFIG));

		store.save(new ClusterDefinition("dev", "Renamed", null, KUBECONFIG));
		assertThat(store.find("dev")).get().extracting(ClusterDefinition::name).isEqualTo("Renamed");
		assertThat(store.find("dev")).get().extracting(ClusterDefinition::context).isNull();

		store.delete("dev");
		assertThat(store.load()).isEmpty();
		assertThat(Files.exists(clusters.resolve("dev.kubeconfig"))).isFalse();
	}

	@Test
	void anIdThatWouldEscapeTheDirectoryIsRefused() {
		FileClusterStore store = new FileClusterStore(this.dir.resolve("clusters"));

		assertThatThrownBy(() -> store.save(new ClusterDefinition("../../etc/passwd", "Bad", null, KUBECONFIG)))
			.isInstanceOf(InvalidClusterException.class);
		assertThatThrownBy(() -> store.delete("../../etc/passwd")).isInstanceOf(InvalidClusterException.class);
	}

	@Test
	void anUnreadableEntryIsSkippedRatherThanFailingTheWholeStore() throws IOException {
		Path clusters = this.dir.resolve("clusters");
		FileClusterStore store = new FileClusterStore(clusters);
		store.save(new ClusterDefinition("dev", "Development", "dev", KUBECONFIG));
		// A metadata file with no matching directory entry readable — simulate by making
		// the metadata a directory, which cannot be read as a properties file.
		Files.createDirectories(clusters.resolve("ghost.properties"));

		assertThat(store.load()).extracting(ClusterDefinition::id).containsExactly("dev");
	}

	@Test
	void inMemoryStoreIsReportedAsNonPersistent() {
		InMemoryClusterStore store = new InMemoryClusterStore();
		store.save(new ClusterDefinition("dev", "Development", "dev", KUBECONFIG));

		assertThat(store.persistent()).isFalse();
		assertThat(store.describe()).contains("lost on restart");
		assertThat(store.load()).hasSize(1);
		store.delete("dev");
		assertThat(store.load()).isEmpty();
	}

}
