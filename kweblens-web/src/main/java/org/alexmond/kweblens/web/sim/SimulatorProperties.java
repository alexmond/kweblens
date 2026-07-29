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

	/** Objects to generate per kind (ConfigMap, Secret, Pod, ReplicaSet, Deployment). */
	private int size = 200;

	/** Number of namespaces to spread the generated objects across. */
	private int namespaces = 3;

}
