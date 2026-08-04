package org.alexmond.kweblens.web.sim;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import io.fabric8.kubernetes.api.model.ContainerBuilder;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.EnvVarBuilder;
import io.fabric8.kubernetes.api.model.Toleration;
import io.fabric8.kubernetes.api.model.TolerationBuilder;
import io.fabric8.kubernetes.api.model.Volume;
import io.fabric8.kubernetes.api.model.VolumeBuilder;

/**
 * The pod-spec furniture a real pod always has and a hand-written one never does:
 * environment variables (including the {@code valueFrom} references that tie a pod to its
 * ConfigMap and Secret), liveness/readiness/startup probes, the two tolerations the
 * scheduler adds to everything, and the projected {@code kube-api-access} volume.
 *
 * <p>
 * These are not padding. The {@code valueFrom} references are a second, independent way a
 * pod consumes a ConfigMap or Secret — the config-usage health check looks for exactly
 * them, and with only volume mounts seeded that half of the check was never exercised.
 * The probes are what make a readiness failure expressible at all. The projected volume
 * is several hundred bytes on every pod in every cluster.
 */
final class SimPodParts {

	private SimPodParts() {
	}

	/** Two to six env vars, some literal, some referencing this pod's own config. */
	static List<EnvVar> env(int index, SimRandom random) {
		List<EnvVar> env = new ArrayList<>();
		int count = random.between(2, 6);
		for (int i = 0; i < count; i++) {
			env.add(new EnvVarBuilder().withName("SIM_" + random.word().toUpperCase(Locale.ROOT) + '_' + i)
				.withValue(random.word() + '-' + random.hex(10))
				.build());
		}
		env.add(new EnvVarBuilder().withName("SIM_CONFIG_KEY")
			.withNewValueFrom()
			.withNewConfigMapKeyRef()
			.withName("sim-config-" + index)
			.withKey("cache-0.conf")
			.withOptional(true)
			.endConfigMapKeyRef()
			.endValueFrom()
			.build());
		env.add(new EnvVarBuilder().withName("SIM_TOKEN")
			.withNewValueFrom()
			.withNewSecretKeyRef()
			.withName("sim-secret-" + index)
			.withKey("token")
			.withOptional(true)
			.endSecretKeyRef()
			.endValueFrom()
			.build());
		env.add(new EnvVarBuilder().withName("POD_NAME")
			.withNewValueFrom()
			.withNewFieldRef()
			.withApiVersion("v1")
			.withFieldPath("metadata.name")
			.endFieldRef()
			.endValueFrom()
			.build());
		return env;
	}

	/**
	 * Liveness and readiness probes, with the fields the API server defaults — and a
	 * startup probe on some, because not every real workload declares one and a rig where
	 * every container is identical has no distribution to measure.
	 */
	static ContainerBuilder probes(ContainerBuilder container, SimRandom random) {
		container.withNewLivenessProbe()
			.withNewHttpGet()
			.withPath("/healthz")
			.withNewPort(8080)
			.withScheme("HTTP")
			.endHttpGet()
			.withFailureThreshold(3)
			.withPeriodSeconds(10)
			.withSuccessThreshold(1)
			.withTimeoutSeconds(1)
			.withInitialDelaySeconds(15)
			.endLivenessProbe();
		container.withNewReadinessProbe()
			.withNewHttpGet()
			.withPath("/readyz")
			.withNewPort(8080)
			.withScheme("HTTP")
			.endHttpGet()
			.withFailureThreshold(3)
			.withPeriodSeconds(5)
			.withSuccessThreshold(1)
			.withTimeoutSeconds(1)
			.endReadinessProbe();
		if (!random.chance(45)) {
			return container;
		}
		return container.withNewStartupProbe()
			.withNewHttpGet()
			.withPath("/healthz")
			.withNewPort(8080)
			.withScheme("HTTP")
			.endHttpGet()
			.withFailureThreshold(30)
			.withPeriodSeconds(10)
			.withSuccessThreshold(1)
			.withTimeoutSeconds(1)
			.endStartupProbe();
	}

	/** The two tolerations the API server adds to every scheduled pod. */
	static List<Toleration> tolerations() {
		Toleration notReady = new TolerationBuilder().withKey("node.kubernetes.io/not-ready")
			.withOperator("Exists")
			.withEffect("NoExecute")
			.withTolerationSeconds(300L)
			.build();
		Toleration unreachable = new TolerationBuilder().withKey("node.kubernetes.io/unreachable")
			.withOperator("Exists")
			.withEffect("NoExecute")
			.withTolerationSeconds(300L)
			.build();
		return List.of(notReady, unreachable);
	}

	/** The projected service-account volume every pod in every cluster carries. */
	static Volume apiAccess() {
		return new VolumeBuilder().withName("kube-api-access")
			.withNewProjected()
			.withDefaultMode(420)
			.addNewSource()
			.withNewServiceAccountToken()
			.withExpirationSeconds(3_607L)
			.withPath("token")
			.endServiceAccountToken()
			.endSource()
			.addNewSource()
			.withNewConfigMap()
			.withName("kube-root-ca.crt")
			.addNewItem()
			.withKey("ca.crt")
			.withPath("ca.crt")
			.endItem()
			.endConfigMap()
			.endSource()
			.addNewSource()
			.withNewDownwardAPI()
			.addNewItem()
			.withNewFieldRef()
			.withApiVersion("v1")
			.withFieldPath("metadata.namespace")
			.endFieldRef()
			.withPath("namespace")
			.endItem()
			.endDownwardAPI()
			.endSource()
			.endProjected()
			.build();
	}

}
