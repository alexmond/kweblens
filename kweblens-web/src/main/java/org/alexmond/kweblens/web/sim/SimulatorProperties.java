package org.alexmond.kweblens.web.sim;

import lombok.Data;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Built-in cluster simulator (dev / CI / performance testing). When
 * {@code kweblens.simulator.enabled} is true, kweblens registers an in-JVM, generated
 * cluster (backed by the fabric8 mock server) with configurable object counts — so the UI
 * can be exercised against large lists (and the watch replay-burst) with no real cluster.
 * Off by default; typically paired with {@code kweblens.load-kubeconfig=false} so the
 * simulator is the only cluster.
 */
@Data
@ConfigurationProperties(prefix = "kweblens.simulator")
public class SimulatorProperties {

	/** Turn the simulator on. */
	private boolean enabled;

	/** Cluster id / name the simulator registers under. */
	private String clusterId = "sim";

	/**
	 * Objects to generate per kind (ConfigMap, Secret, Pod, ReplicaSet, Deployment,
	 * Service, Endpoints, Ingress).
	 */
	private int size = 200;

	/** Number of namespaces to spread the generated objects across. */
	private int namespaces = 3;

	/**
	 * Multiplier on generated ConfigMap/Secret data sizes.
	 *
	 * <p>
	 * The generated objects are sized to match a real cluster's — a Secret averages ~50
	 * KB with a tail past 900 KB — because a rig whose objects are unrepresentative
	 * measures the rig. The honest cost is memory: at {@code size=3000} the Secrets alone
	 * are ~150 MB held in the mock API server. Turn this down (0.1, or 0 for keys with no
	 * bodies) for a sweep that needs many rows rather than realistic ones, and say so
	 * when quoting any payload number taken from that run.
	 */
	private double payloadScale = 1.0;

}
