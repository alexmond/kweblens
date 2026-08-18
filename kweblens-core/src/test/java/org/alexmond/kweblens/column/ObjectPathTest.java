package org.alexmond.kweblens.column;

import java.util.List;
import java.util.Map;

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.client.utils.Serialization;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The evaluator behind the 35 columns that are one dotted path and nothing else.
 *
 * <p>
 * Two of these are the ones that matter: a <b>nested</b> read has to walk maps the client
 * left as maps, and a <b>missing</b> path has to render an em dash rather than an empty
 * cell — "we did not send it" and "it is empty" are different claims, and a blank cell
 * under a heading makes the second one.
 */
class ObjectPathTest {

	private static final String NODE = """
			apiVersion: v1
			kind: Node
			metadata:
			  name: cp-1
			  labels:
			    kubernetes.io/os: linux
			spec:
			  taints:
			  - key: dedicated
			    value: etcd
			status:
			  nodeInfo:
			    kubeletVersion: v1.31.3+k3s1
			  addresses:
			  - type: InternalIP
			    address: 192.0.2.5
			""";

	private final GenericKubernetesResource node = Serialization.unmarshal(NODE, GenericKubernetesResource.class);

	@Test
	void readsANestedPath() {
		assertThat(ObjectPath.read(this.node, "status.nodeInfo.kubeletVersion")).isEqualTo("v1.31.3+k3s1");
	}

	@Test
	void aPathThatGoesNowhereIsAbsentAndRendersAsADash() {
		assertThat(ObjectPath.read(this.node, "status.nodeInfo.osImage")).isNull();
		assertThat(ObjectPath.read(this.node, "status.nowhere.at.all")).isNull();
		assertThat(Column.path("os", "OS Image", "status.nodeInfo.osImage").render(this.node))
			.isEqualTo(ColumnCatalog.MISSING_CELL);
	}

	@Test
	void descendingThroughSomethingThatIsNotAMapIsAbsentRatherThanAnError() {
		assertThat(ObjectPath.read(this.node, "status.nodeInfo.kubeletVersion.major")).isNull();
	}

	@Test
	void metadataIsProjectedBecauseFabric8KeepsItOutOfTheBag() {
		assertThat(ObjectPath.read(this.node, "metadata.name")).isEqualTo("cp-1");
		assertThat(ObjectPath.read(this.node, "metadata.labels")).isEqualTo(Map.of("kubernetes.io/os", "linux"));
	}

	/**
	 * A dotted path cannot name a key that itself contains a dot — which is every
	 * Kubernetes label. That is a real limit and it is the SPA's too ({@code getDotted}
	 * splits on the same character), so it is pinned here rather than discovered later:
	 * anything that wants a label reads the label map, as {@code NodeColumns} does.
	 */
	@Test
	void aKeyWithADotInItCannotBeNamedByADottedPath() {
		assertThat(ObjectPath.read(this.node, "metadata.labels.kubernetes.io/os")).isNull();
	}

	@Test
	void aNumericSegmentIndexesAnArray() {
		assertThat(ObjectPath.read(this.node, "spec.taints.0.value")).isEqualTo("etcd");
		assertThat(ObjectPath.read(this.node, "spec.taints.9.value")).isNull();
		assertThat(ObjectPath.read(this.node, "spec.taints.key")).isNull();
	}

	@Test
	void listAndMapAnswerEmptyRatherThanNullSoARendererNeedNotCheck() {
		assertThat(ObjectPath.list(this.node, "status.conditions")).isEmpty();
		assertThat(ObjectPath.map(this.node, "status.capacity")).isEmpty();
		assertThat(ObjectPath.list(this.node, "status.addresses")).hasSize(1);
		assertThat(ObjectPath.field(ObjectPath.list(this.node, "status.addresses").get(0), "address"))
			.isEqualTo("192.0.2.5");
		assertThat(ObjectPath.field("not a map", "address")).isNull();
	}

	@Test
	void aNullObjectOrAnEmptyPathIsAbsent() {
		assertThat(ObjectPath.read(null, "metadata.name")).isNull();
		assertThat(ObjectPath.read(this.node, "")).isNull();
		assertThat(ObjectPath.descend(List.of(), "")).isEqualTo(List.of());
	}

}
