package org.alexmond.kweblens.tui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.alexmond.kweblens.cluster.ClusterInfo;
import org.alexmond.kweblens.cluster.ClusterRegistry;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * One cluster per kubeconfig context, id = the context name.
 *
 * <p>
 * Hermetic despite reading a kubeconfig: building a fabric8 client does not connect, so
 * registering a context reaches nothing. The kubeconfig here is written to a temp
 * directory and points at addresses that are never dialled.
 */
class TuiClusterBootstrapTest {

	@TempDir
	Path directory;

	private static final String TWO_CONTEXTS = """
			apiVersion: v1
			kind: Config
			current-context: second
			clusters:
			  - name: one
			    cluster:
			      server: https://one.invalid:6443
			  - name: two
			    cluster:
			      server: https://two.invalid:6443
			contexts:
			  - name: first
			    context:
			      cluster: one
			      user: nobody
			  - name: second
			    context:
			      cluster: two
			      user: nobody
			users:
			  - name: nobody
			    user: {}
			""";

	private TuiProperties propertiesFor(Path kubeconfig) {
		TuiProperties properties = new TuiProperties();
		properties.setKubeconfig((kubeconfig != null) ? kubeconfig.toString() : null);
		return properties;
	}

	private Path writeKubeconfig(String yaml) throws IOException {
		Path path = this.directory.resolve("config");
		Files.writeString(path, yaml);
		return path;
	}

	@Test
	void registersOneClusterPerContextUnderTheContextsOwnName() throws IOException {
		ClusterRegistry registry = new ClusterRegistry();
		TuiClusterBootstrap bootstrap = new TuiClusterBootstrap(propertiesFor(writeKubeconfig(TWO_CONTEXTS)), registry);

		assertThat(bootstrap.load()).containsExactly("first", "second");
		assertThat(registry.list().stream().map(ClusterInfo::id)).containsExactlyInAnyOrder("first", "second");
	}

	@Test
	void currentContextIsWhatKubectlWouldOpen() throws IOException {
		TuiClusterBootstrap bootstrap = new TuiClusterBootstrap(propertiesFor(writeKubeconfig(TWO_CONTEXTS)),
				new ClusterRegistry());

		assertThat(bootstrap.currentContext()).isEqualTo("second");
	}

	@Test
	void loadingOffMeansTheRegistryStaysEmpty() throws IOException {
		TuiProperties properties = propertiesFor(writeKubeconfig(TWO_CONTEXTS));
		properties.setLoadKubeconfig(false);
		ClusterRegistry registry = new ClusterRegistry();

		assertThat(new TuiClusterBootstrap(properties, registry).load()).isEmpty();
		assertThat(new TuiClusterBootstrap(properties, registry).currentContext()).isNull();
		assertThat(registry.list()).isEmpty();
	}

	@Test
	void anUnreadableKubeconfigIsNoClustersRatherThanAClusterCalledDefault() {
		TuiProperties properties = propertiesFor(this.directory.resolve("not-there"));
		ClusterRegistry registry = new ClusterRegistry();

		assertThat(new TuiClusterBootstrap(properties, registry).load()).isEmpty();
		assertThat(registry.list())
			.as("'default' is the server's name for an in-cluster service account; "
					+ "inventing it here would open a cluster nobody asked for")
			.isEmpty();
	}

	@Test
	void unparseableKubeconfigIsReportedAsNoClustersRatherThanThrowing() throws IOException {
		TuiClusterBootstrap bootstrap = new TuiClusterBootstrap(propertiesFor(writeKubeconfig("::not yaml::")),
				new ClusterRegistry());

		assertThat(bootstrap.load()).isEmpty();
		assertThat(bootstrap.currentContext()).isNull();
	}

}
