package org.alexmond.kweblens.health;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.ServiceAccount;
import io.fabric8.kubernetes.api.model.Volume;
import io.fabric8.kubernetes.api.model.VolumeProjection;
import io.fabric8.kubernetes.api.model.networking.v1.Ingress;

/**
 * Collects the ConfigMap and Secret names that objects in a namespace refer to.
 *
 * <p>
 * This is the evidence behind "nothing references this", and the value of that claim is
 * entirely a function of how thoroughly it looks. Missing a reference kind here does not
 * produce a smaller answer — it produces a <b>wrong</b> one, listing an object as unused
 * that something depends on. So this covers every way a namespace's objects name a
 * ConfigMap or Secret: pod volumes (including projected sources), {@code envFrom}, single
 * {@code valueFrom} keys, image-pull secrets, the secrets and pull-secrets a
 * ServiceAccount carries, and the TLS secrets an Ingress terminates with.
 *
 * <p>
 * What it still cannot see is stated with the result rather than papered over: references
 * from controller templates whose pods have not been created, from other namespaces, and
 * from CRDs and webhook configurations that name a Secret in their own spec.
 */
final class ConfigReferences {

	private final Set<String> configMaps = new HashSet<>();

	private final Set<String> secrets = new HashSet<>();

	boolean referencesConfigMap(String name) {
		return this.configMaps.contains(name);
	}

	boolean referencesSecret(String name) {
		return this.secrets.contains(name);
	}

	void addPods(List<Pod> pods) {
		for (Pod pod : pods) {
			if (pod.getSpec() == null) {
				continue;
			}
			addVolumes(pod.getSpec().getVolumes());
			addContainers(pod.getSpec().getContainers());
			addContainers(pod.getSpec().getInitContainers());
			if (pod.getSpec().getImagePullSecrets() != null) {
				pod.getSpec().getImagePullSecrets().forEach((ref) -> add(this.secrets, ref.getName()));
			}
		}
	}

	void addServiceAccounts(List<ServiceAccount> accounts) {
		for (ServiceAccount account : accounts) {
			if (account.getSecrets() != null) {
				account.getSecrets().forEach((ref) -> add(this.secrets, ref.getName()));
			}
			if (account.getImagePullSecrets() != null) {
				account.getImagePullSecrets().forEach((ref) -> add(this.secrets, ref.getName()));
			}
		}
	}

	void addIngresses(List<Ingress> ingresses) {
		for (Ingress ingress : ingresses) {
			if (ingress.getSpec() == null || ingress.getSpec().getTls() == null) {
				continue;
			}
			ingress.getSpec().getTls().forEach((tls) -> add(this.secrets, tls.getSecretName()));
		}
	}

	private void addVolumes(List<Volume> volumes) {
		if (volumes == null) {
			return;
		}
		for (Volume volume : volumes) {
			if (volume.getConfigMap() != null) {
				add(this.configMaps, volume.getConfigMap().getName());
			}
			if (volume.getSecret() != null) {
				add(this.secrets, volume.getSecret().getSecretName());
			}
			// A projected volume nests the same sources one level down; the
			// service-account token volume every pod gets is one of these, and it is how
			// the cluster's kube-root-ca.crt ConfigMap is referenced.
			if (volume.getProjected() != null && volume.getProjected().getSources() != null) {
				volume.getProjected().getSources().forEach(this::addProjection);
			}
		}
	}

	private void addProjection(VolumeProjection projection) {
		if (projection.getConfigMap() != null) {
			add(this.configMaps, projection.getConfigMap().getName());
		}
		if (projection.getSecret() != null) {
			add(this.secrets, projection.getSecret().getName());
		}
	}

	private void addContainers(List<Container> containers) {
		if (containers == null) {
			return;
		}
		for (Container container : containers) {
			if (container.getEnvFrom() != null) {
				for (var from : container.getEnvFrom()) {
					if (from.getConfigMapRef() != null) {
						add(this.configMaps, from.getConfigMapRef().getName());
					}
					if (from.getSecretRef() != null) {
						add(this.secrets, from.getSecretRef().getName());
					}
				}
			}
			if (container.getEnv() != null) {
				container.getEnv().forEach(this::addEnv);
			}
		}
	}

	private void addEnv(EnvVar env) {
		if (env.getValueFrom() == null) {
			return;
		}
		if (env.getValueFrom().getConfigMapKeyRef() != null) {
			add(this.configMaps, env.getValueFrom().getConfigMapKeyRef().getName());
		}
		if (env.getValueFrom().getSecretKeyRef() != null) {
			add(this.secrets, env.getValueFrom().getSecretKeyRef().getName());
		}
	}

	private void add(Set<String> into, String name) {
		if (name != null && !name.isBlank()) {
			into.add(name);
		}
	}

}
