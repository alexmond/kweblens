package org.alexmond.kweblens.web.sim;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.stream.IntStream;

import io.fabric8.kubernetes.api.model.Node;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.Volume;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import io.fabric8.kubernetes.client.utils.Serialization;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The seeder produces the configured counts — and objects that <b>resemble real ones</b>,
 * which is the property the rig is actually for.
 *
 * <p>
 * The size assertions have generous floors rather than tight ranges on purpose: they are
 * there to fail if a future change quietly strips managedFields or the data bodies again
 * (the regression that made the last set of scale measurements wrong by 50-500x), not to
 * pin a byte count that legitimately drifts. The measured live-cluster figures they are
 * calibrated against are in {@code docs/design/scale-measurements.md}.
 */
@EnableKubernetesMockClient(crud = true)
class SimulatorSeederTest {

	KubernetesClient client;

	SimulatorProperties props;

	@BeforeEach
	void seed() {
		this.props = new SimulatorProperties();
		this.props.setSize(40);
		this.props.setNamespaces(2);
		SimulatorSeeder.seed(this.client, this.props);
	}

	@Test
	void seedsConfigurableCountsPerKindAndNamespace() {
		assertThat(this.client.namespaces().list().getItems()).hasSize(2);
		assertThat(this.client.configMaps().inAnyNamespace().list().getItems()).hasSize(40);
		assertThat(this.client.secrets().inAnyNamespace().list().getItems()).hasSize(40);
		assertThat(this.client.pods().inAnyNamespace().list().getItems()).hasSize(40);
		assertThat(this.client.apps().replicaSets().inAnyNamespace().list().getItems()).hasSize(40);
		assertThat(this.client.apps().deployments().inAnyNamespace().list().getItems()).hasSize(40);
		assertThat(this.client.services().inAnyNamespace().list().getItems()).hasSize(40);
		assertThat(this.client.network().v1().ingresses().inAnyNamespace().list().getItems()).hasSize(40);
		// Objects spread across both namespaces (index % 2).
		assertThat(this.client.configMaps().inNamespace("sim-ns-0").list().getItems()).hasSize(20);
		assertThat(this.client.configMaps().inNamespace("sim-ns-1").list().getItems()).hasSize(20);
	}

	@Test
	void objectsCarryManagedFieldsAndAnnotations() {
		Pod pod = this.client.pods().inNamespace("sim-ns-0").withName("sim-pod-0").get();
		// managedFields is 37-48% of a real workload payload and was 0% of a seeded one.
		// Without it every payload number taken against this rig is a number about the
		// rig.
		assertThat(pod.getMetadata().getManagedFields()).hasSizeGreaterThanOrEqualTo(3);
		assertThat(pod.getMetadata().getManagedFields().getFirst().getFieldsV1().getAdditionalProperties())
			.containsKey("f:metadata");
		assertThat(pod.getMetadata().getAnnotations()).containsKey("meta.helm.sh/release-name");
		assertThat(pod.getMetadata().getUid()).isNotBlank();
		assertThat(pod.getMetadata().getCreationTimestamp()).isNotBlank();
		assertThat(
				this.client.network().v1().ingresses().inAnyNamespace().list().getItems().getFirst().getSpec().getTls())
			.isNotEmpty();
	}

	@Test
	void objectsAreWithinReachOfLiveClusterSizes() {
		assertThat(bytes(this.client.pods().inNamespace("sim-ns-0").withName("sim-pod-0").get()))
			.as("a real pod is ~7.8 KB")
			.isGreaterThan(4_000);
		assertThat(bytes(this.client.apps().deployments().inNamespace("sim-ns-0").withName("sim-deploy-0").get()))
			.as("a real deployment is ~5.9 KB")
			.isGreaterThan(3_000);
		assertThat(bytes(this.client.nodes().withName("node-0.sim.example.test").get())).as("a real node is ~10.3 KB")
			.isGreaterThan(5_000);
		assertThat(bytes(this.client.services().inNamespace("sim-ns-0").withName("sim-svc-0").get()))
			.as("a real service is ~1.6 KB")
			.isGreaterThan(900);
	}

	/**
	 * The tail, not the mean: the largest Secret on the live cluster is 673 KB against a
	 * 53 KB average, and a rig where every object is the mean cannot reproduce the
	 * failures that only the tail produces.
	 */
	@Test
	void configAndSecretDataSpanOrdersOfMagnitude() {
		List<Integer> secrets = this.client.secrets()
			.inAnyNamespace()
			.list()
			.getItems()
			.stream()
			.map(this::bytes)
			.sorted()
			.toList();
		assertThat(secrets.getLast()).as("the tail").isGreaterThan(20 * secrets.get(secrets.size() / 2));
		List<Integer> configMaps = this.client.configMaps()
			.inAnyNamespace()
			.list()
			.getItems()
			.stream()
			.map(this::bytes)
			.sorted()
			.toList();
		assertThat(configMaps.getLast()).isGreaterThan(10 * configMaps.get(configMaps.size() / 2));
	}

	/**
	 * A Secret's values are base64 on the wire; a fixture that is not is a broken drawer.
	 */
	@Test
	void secretValuesAreValidBase64() {
		Secret secret = this.client.secrets().inNamespace("sim-ns-0").withName("sim-secret-0").get();
		assertThat(secret.getData()).isNotEmpty();
		secret.getData().values().forEach((v) -> assertThat(Base64.getDecoder().decode(v)).isNotEmpty());
	}

	/**
	 * A healthy-only rig is why {@code .ov-card.danger} and the status pills have never
	 * been measurable. Each of these states has to exist at a small {@code size} too,
	 * which is what the deterministic index roll buys.
	 */
	@Test
	void seedsAMinorityOfUnhealthyObjects() {
		List<Pod> pods = this.client.pods().inAnyNamespace().list().getItems();
		assertThat(waitingReasons(pods)).contains(SimPods.CRASH_LOOP, SimPods.IMAGE_PULL);
		assertThat(pods).anyMatch((p) -> SimPods.PENDING.equals(p.getStatus().getPhase())
				&& (p.getSpec().getNodeName() == null || p.getSpec().getNodeName().isEmpty()));
		assertThat(pods).anyMatch((p) -> SimPods.SUCCEEDED.equals(p.getStatus().getPhase()));
		// ...and a clear majority still fine, or the rig is a different lie.
		assertThat(pods.stream().filter((p) -> SimPods.RUNNING.equals(SimPods.state(index(p)))).count())
			.isGreaterThan(pods.size() / 2L);
		// A Service with no Endpoints object at all: the check NetworkHealthService runs.
		assertThat(this.client.endpoints().inAnyNamespace().list().getItems())
			.hasSizeLessThan(this.client.services().inAnyNamespace().list().getItems().size());
		assertThat(this.client.v1().events().inAnyNamespace().list().getItems())
			.anyMatch((e) -> "Warning".equals(e.getType()));
		assertThat(this.client.nodes().list().getItems()).anyMatch(SimulatorSeederTest::notReady);
	}

	/**
	 * Every state the dashboard can render occurs inside the first hundred indices, and
	 * they stay a minority.
	 *
	 * <p>
	 * That bound is the useful property, not the percentages: it is what lets someone
	 * reproduce a state-specific defect with {@code size=100} instead of waiting out a 3
	 * 000-object seed. Asserted on the pure state function rather than on seeded objects
	 * so it costs nothing and says exactly what it means.
	 */
	@Test
	void everyPodStateOccursWithinTheFirstHundredIndices() {
		List<String> states = IntStream.range(0, 100).mapToObj(SimPods::state).toList();
		assertThat(states).contains(SimPods.CRASH_LOOP, SimPods.IMAGE_PULL, SimPods.PENDING, SimPods.OOM_KILLED,
				SimPods.FAILED, SimPods.SUCCEEDED, SimPods.RUNNING);
		assertThat(states.stream().filter(SimPods.RUNNING::equals).count()).isBetween(75L, 90L);
	}

	/**
	 * Every scheduled pod is on a node and mounts the ConfigMap and Secret of its own
	 * index — what makes the drawer's relation sections resolvable with no live cluster,
	 * and what puts a value in the Node column that #278 was about. An unschedulable pod
	 * legitimately has no node, which is the point of seeding one.
	 */
	@Test
	void scheduledPodsMountTheirConfigAndSitOnANode() {
		this.client.pods()
			.inAnyNamespace()
			.list()
			.getItems()
			.stream()
			.filter((p) -> !SimPods.PENDING.equals(p.getStatus().getPhase()))
			.forEach((p) -> assertThat(p.getSpec().getNodeName()).startsWith("node-").endsWith(".sim.example.test"));
		List<Volume> volumes = this.client.pods()
			.inNamespace("sim-ns-0")
			.withName("sim-pod-0")
			.get()
			.getSpec()
			.getVolumes();
		assertThat(volumes).map((v) -> (v.getSecret() != null) ? v.getSecret().getSecretName() : null)
			.contains("sim-secret-0");
		assertThat(volumes).map((v) -> (v.getConfigMap() != null) ? v.getConfigMap().getName() : null)
			.contains("sim-config-0");
	}

	/** The same index yields the same object every run, so two runs are comparable. */
	@Test
	void generationIsDeterministic() {
		assertThat(bytes(SimConfigs.configMap(7, "sim-ns-0", 1.0)))
			.isEqualTo(bytes(SimConfigs.configMap(7, "sim-ns-0", 1.0)));
		assertThat(SimPods.state(7)).isEqualTo(SimPods.state(7));
	}

	/** payload-scale is the documented way out of the memory cost of realistic data. */
	@Test
	void payloadScaleShrinksTheData() {
		assertThat(bytes(SimConfigs.secret(3, "sim-ns-0", 0.01)))
			.isLessThan(bytes(SimConfigs.secret(3, "sim-ns-0", 1.0)));
	}

	private static boolean notReady(Node node) {
		return node.getStatus()
			.getConditions()
			.stream()
			.anyMatch((c) -> "Ready".equals(c.getType()) && !"True".equals(c.getStatus()));
	}

	private static List<String> waitingReasons(List<Pod> pods) {
		return pods.stream()
			.filter((p) -> p.getStatus().getContainerStatuses() != null)
			.flatMap((p) -> p.getStatus().getContainerStatuses().stream())
			.filter((c) -> c.getState() != null && c.getState().getWaiting() != null)
			.map((c) -> c.getState().getWaiting().getReason())
			.toList();
	}

	private static int index(Pod pod) {
		return Integer.parseInt(pod.getMetadata().getName().substring("sim-pod-".length()));
	}

	private int bytes(Object object) {
		return Serialization.asJson(object).getBytes(StandardCharsets.UTF_8).length;
	}

}
