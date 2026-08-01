package org.alexmond.kweblens.resource;

import java.util.ArrayList;
import java.util.List;

import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodSpec;
import io.fabric8.kubernetes.api.model.Volume;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.alexmond.kweblens.cluster.ClusterRegistry;

/**
 * The reverse question — "what is using this?" — for the things a pod consumes: Secrets,
 * ConfigMaps and PersistentVolumeClaims.
 *
 * <p>
 * It is the reverse direction that has to be a server-side join. A pod names what it
 * consumes, so the forward direction is readable from the pod alone; nothing on a Secret,
 * ConfigMap or PVC records who consumes it, and the API server offers no selector for it.
 * So this lists the namespace's pods and filters locally, which is exactly why it is
 * bounded and namespace-scoped: a cluster-wide version of this query is genuinely
 * expensive, and a drawer is not the place to pay for it.
 */
@Slf4j
@RequiredArgsConstructor
class ReferenceRelations {

	private final ClusterRegistry clusters;

	/**
	 * Which pods use this Secret, ConfigMap or PersistentVolumeClaim.
	 *
	 * <p>
	 * For a PVC this is the answer to two questions the object itself refuses: why a
	 * Terminating claim will not go away (a pod still mounts it, so the protection
	 * finalizer holds), and why a ReadWriteOnce claim will not attach to a new pod (the
	 * old one still has it, on another node).
	 *
	 * <p>
	 * Cost: one namespace LIST of pods, whatever the kind — the three kinds share the one
	 * scan, so a drawer never pays for it twice.
	 */
	Relation mountedBy(String clusterId, String namespace, String kind, String name) {
		try {
			List<Pod> pods = this.clusters.require(clusterId).pods().inNamespace(namespace).list().getItems();
			List<Object> matches = new ArrayList<>();
			for (Pod pod : pods) {
				if (podReferences(pod, kind, name)) {
					matches.add(pod);
				}
			}
			return RelationSupport.bound(matches);
		}
		catch (RuntimeException ex) {
			log.debug("mountedBy scan failed in {}: {}", namespace, ex.getMessage());
			return Relation.from(ex);
		}
	}

	private boolean podReferences(Pod pod, String kind, String name) {
		PodSpec spec = pod.getSpec();
		if (spec == null) {
			return false;
		}
		if ("PersistentVolumeClaim".equals(kind)) {
			return claimReferences(spec, name);
		}
		boolean secret = "Secret".equals(kind);
		if (secret && pullSecretReferences(spec, name)) {
			return true;
		}
		return volumeReferences(spec, secret, name) || containerReferences(spec, secret, name);
	}

	private boolean claimReferences(PodSpec spec, String name) {
		if (spec.getVolumes() == null) {
			return false;
		}
		return spec.getVolumes()
			.stream()
			.anyMatch((v) -> v.getPersistentVolumeClaim() != null
					&& name.equals(v.getPersistentVolumeClaim().getClaimName()));
	}

	/**
	 * {@code imagePullSecrets} counts. A registry credential is referenced nowhere else
	 * in the pod, and "which pods break if I rotate this" is the reason someone opens a
	 * pull secret's drawer at all.
	 */
	private boolean pullSecretReferences(PodSpec spec, String name) {
		return spec.getImagePullSecrets() != null
				&& spec.getImagePullSecrets().stream().anyMatch((s) -> name.equals(s.getName()));
	}

	private boolean volumeReferences(PodSpec spec, boolean secret, String name) {
		if (spec.getVolumes() == null) {
			return false;
		}
		return spec.getVolumes().stream().anyMatch((v) -> directVolume(v, secret, name) || projected(v, secret, name));
	}

	private boolean directVolume(Volume volume, boolean secret, String name) {
		if (secret) {
			return volume.getSecret() != null && name.equals(volume.getSecret().getSecretName());
		}
		return volume.getConfigMap() != null && name.equals(volume.getConfigMap().getName());
	}

	/**
	 * Projected volumes hide a real reference one level down. Missing them would report
	 * "nothing uses this ConfigMap" about the CA bundle every pod in the namespace
	 * projects — a wrong answer that reads like a safe one.
	 */
	private boolean projected(Volume volume, boolean secret, String name) {
		if (volume.getProjected() == null || volume.getProjected().getSources() == null) {
			return false;
		}
		return volume.getProjected().getSources().stream().anyMatch((source) -> {
			if (secret) {
				return source.getSecret() != null && name.equals(source.getSecret().getName());
			}
			return source.getConfigMap() != null && name.equals(source.getConfigMap().getName());
		});
	}

	private boolean containerReferences(PodSpec spec, boolean secret, String name) {
		List<Container> containers = new ArrayList<>();
		if (spec.getContainers() != null) {
			containers.addAll(spec.getContainers());
		}
		if (spec.getInitContainers() != null) {
			containers.addAll(spec.getInitContainers());
		}
		for (Container container : containers) {
			if (envFromReferences(container, secret, name)) {
				return true;
			}
			if (container.getEnv() != null
					&& container.getEnv().stream().anyMatch((e) -> envReferences(e, secret, name))) {
				return true;
			}
		}
		return false;
	}

	private boolean envFromReferences(Container container, boolean secret, String name) {
		if (container.getEnvFrom() == null) {
			return false;
		}
		return container.getEnvFrom().stream().anyMatch((from) -> {
			if (secret) {
				return from.getSecretRef() != null && name.equals(from.getSecretRef().getName());
			}
			return from.getConfigMapRef() != null && name.equals(from.getConfigMapRef().getName());
		});
	}

	private boolean envReferences(EnvVar env, boolean secret, String name) {
		if (env.getValueFrom() == null) {
			return false;
		}
		if (secret) {
			var ref = env.getValueFrom().getSecretKeyRef();
			return ref != null && name.equals(ref.getName());
		}
		var ref = env.getValueFrom().getConfigMapKeyRef();
		return ref != null && name.equals(ref.getName());
	}

}
