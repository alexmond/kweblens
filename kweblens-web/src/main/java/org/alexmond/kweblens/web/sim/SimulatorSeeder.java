package org.alexmond.kweblens.web.sim;

import java.util.LinkedHashMap;
import java.util.Map;

import io.fabric8.kubernetes.api.model.Endpoints;
import io.fabric8.kubernetes.api.model.Namespace;
import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;

/**
 * Generates a configurable set of Kubernetes objects into a (mock-backed) client, so the
 * simulator cluster has large lists to render and watch.
 *
 * <p>
 * <b>The objects are meant to resemble real ones, and that is the whole point.</b> The
 * first version of this seeder produced a 739-byte pod and a 289-byte Secret against a
 * live cluster's 7.8 KB and 53.4 KB — no managedFields, no annotations, no real data, one
 * container, no status. A payload measurement taken against it was wrong by 50-500x, and
 * {@code docs/design/scale-measurements.md} had to be corrected after its central
 * conclusion ("the API is comfortable") turned out to be a statement about the rig. A rig
 * whose objects are unrepresentative measures the rig.
 *
 * <p>
 * So the per-kind builders here aim at measured live-cluster shapes — size, distribution,
 * managedFields share — and at states a healthy cluster never shows: crash-looping pods,
 * unschedulable pods, evictions, Services with no endpoints, a NotReady node, Warning
 * events. Everything is deterministic in the object's index (see {@link SimRandom}), so
 * two runs of the rig are comparable and a defect found at index 17 reproduces at
 * {@code size=20}.
 *
 * <p>
 * Kept out of the Spring wiring so it is unit-testable against a crud mock client.
 */
public final class SimulatorSeeder {

	/**
	 * How many Nodes the simulated cluster has. A small fixed number rather than
	 * {@code size}: nodes are the one kind a real cluster has few of however many
	 * workloads it runs, and three is enough for pods to spread over more than one name.
	 */
	private static final int NODES = 3;

	private SimulatorSeeder() {
	}

	/**
	 * Create {@code namespaces} namespaces, then {@code size} of each kind spread across
	 * them. Returns what was created and how long it took, because seeding cost is a real
	 * constraint on this rig — richer objects are slower to build and to store, and the
	 * trade only stays honest if the number is in front of whoever changes it.
	 */
	public static Seeded seed(KubernetesClient client, SimulatorProperties props) {
		long started = System.nanoTime();
		int nsCount = Math.max(1, props.getNamespaces());
		int objects = 0;
		for (int n = 0; n < nsCount; n++) {
			client.namespaces().resource(namespace(ns(n))).create();
			objects++;
		}
		for (int n = 0; n < NODES; n++) {
			client.nodes().resource(SimNodes.node(n, NODES)).create();
			objects++;
		}
		objects += seedNamespaced(client, props, nsCount);
		objects += seedEvents(client, props, nsCount);
		return new Seeded(objects, (System.nanoTime() - started) / 1_000_000L);
	}

	private static int seedNamespaced(KubernetesClient client, SimulatorProperties props, int nsCount) {
		double scale = props.getPayloadScale();
		int objects = 0;
		for (int i = 0; i < props.getSize(); i++) {
			String ns = ns(i % nsCount);
			client.configMaps().resource(SimConfigs.configMap(i, ns, scale)).create();
			client.secrets().resource(SimConfigs.secret(i, ns, scale)).create();
			client.pods().resource(SimPods.pod(i, ns, SimNodes.nodeName(i % NODES), props.getSize())).create();
			client.apps().replicaSets().resource(SimWorkloads.replicaSet(i, ns)).create();
			client.apps().deployments().resource(SimWorkloads.deployment(i, ns)).create();
			client.services().resource(SimNetwork.service(i, ns)).create();
			client.network().v1().ingresses().resource(SimNetwork.ingress(i, ns)).create();
			objects += 7;
			Endpoints endpoints = SimNetwork.endpoints(i, ns, props.getSize());
			if (endpoints != null) {
				client.endpoints().resource(endpoints).create();
				objects++;
			}
		}
		return objects;
	}

	/**
	 * Events for the pods that deserve one, capped: Kubernetes expires events after an
	 * hour, so a cluster ten times the size does not have ten times the events, and a
	 * seeder that scaled them would have built the same unrepresentative rig in a new
	 * place.
	 */
	private static int seedEvents(KubernetesClient client, SimulatorProperties props, int nsCount) {
		int seeded = 0;
		for (int i = 0; i < props.getSize() && seeded < SimEvents.MAX_EVENTS; i++) {
			if (SimEvents.interesting(i)) {
				client.v1().events().resource(SimEvents.event(i, ns(i % nsCount))).create();
				seeded++;
			}
		}
		return seeded;
	}

	private static String ns(int n) {
		return "sim-ns-" + n;
	}

	/**
	 * A namespace with the labels a real one carries —
	 * {@code kubernetes.io/metadata.name} is added by an admission plugin to every
	 * namespace on every cluster, and things select on it.
	 */
	private static Namespace namespace(String name) {
		Map<String, String> labels = new LinkedHashMap<>();
		labels.put("kubernetes.io/metadata.name", name);
		labels.put("app.kubernetes.io/managed-by", "kweblens-simulator");
		return new NamespaceBuilder().withNewMetadata()
			.withName(name)
			.withLabels(labels)
			.withUid(SimMeta.uid("Namespace", name.hashCode()))
			.endMetadata()
			.withNewSpec()
			.withFinalizers("kubernetes")
			.endSpec()
			.withNewStatus()
			.withPhase("Active")
			.endStatus()
			.build();
	}

	/** What one seeding run produced, and what it cost. */
	public record Seeded(int objects, long millis) {
	}

}
