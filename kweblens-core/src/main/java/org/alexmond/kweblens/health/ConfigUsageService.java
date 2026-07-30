package org.alexmond.kweblens.health;

import java.util.ArrayList;
import java.util.List;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.client.KubernetesClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;

import org.alexmond.kweblens.cluster.ClusterRegistry;

/**
 * The Config overview's check: <b>ConfigMaps and Secrets that nothing in the namespace
 * refers to</b>.
 *
 * <p>
 * Config objects accumulate. Nothing in Kubernetes garbage-collects them, nothing warns
 * about them, and a namespace that has been through a few migrations tends to carry
 * config nobody can account for. This is the one question the API cannot answer directly
 * and no other kweblens screen answers at all.
 *
 * <p>
 * <b>It is deliberately framed as "not referenced", not "safe to delete".</b> The scan is
 * bounded to one namespace and to the object kinds in {@link ConfigReferences}, so an
 * object referenced from a controller template whose pods do not exist yet, from another
 * namespace, or from a CRD's own spec will appear here while being very much in use.
 * Deleting on the strength of this list alone would be a mistake, so the wording must not
 * invite it.
 *
 * <p>
 * Two Secret types are excluded outright rather than listed and explained:
 * service-account tokens (the cluster creates and owns them) and Helm release records,
 * which look unused because nothing mounts them and are the exact opposite of disposable
 * — they are a release's history.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConfigUsageService {

	private static final String SERVICE_ACCOUNT_TOKEN = "kubernetes.io/service-account-token";

	private static final String HELM_RELEASE = "helm.sh/release.v1";

	private static final String UNREFERENCED = "not referenced in this namespace";

	private final ClusterRegistry clusters;

	public List<KindHealth> summarise(String clusterId, String namespace) {
		List<KindHealth> out = new ArrayList<>();
		ConfigReferences references;
		try {
			references = scan(clusterId, namespace);
		}
		catch (RuntimeException ex) {
			// Without the reference scan nothing can be judged, and reporting "0 unused"
			// here would be the dangerous direction: it reads as a clean result.
			log.debug("Config reference scan failed: {}", ex.getMessage());
			String message = String.valueOf(ex.getMessage());
			out.add(KindHealth.failed("configmaps", "Config Maps", "ConfigMap", message));
			out.add(KindHealth.failed("secrets", "Secrets", "Secret", message));
			return out;
		}
		out.add(configMaps(clusterId, namespace, references));
		out.add(secrets(clusterId, namespace, references));
		return out;
	}

	private ConfigReferences scan(String clusterId, String namespace) {
		KubernetesClient client = this.clusters.require(clusterId);
		ConfigReferences references = new ConfigReferences();
		references.addPods(
				((namespace != null) ? client.pods().inNamespace(namespace) : client.pods().inAnyNamespace()).list()
					.getItems());
		references.addServiceAccounts(((namespace != null) ? client.serviceAccounts().inNamespace(namespace)
				: client.serviceAccounts().inAnyNamespace())
			.list()
			.getItems());
		references.addIngresses(((namespace != null) ? client.network().v1().ingresses().inNamespace(namespace)
				: client.network().v1().ingresses().inAnyNamespace())
			.list()
			.getItems());
		return references;
	}

	private KindHealth configMaps(String clusterId, String namespace, ConfigReferences references) {
		try {
			var client = this.clusters.require(clusterId).configMaps();
			List<ConfigMap> all = ((namespace != null) ? client.inNamespace(namespace) : client.inAnyNamespace()).list()
				.getItems();
			Tally tally = new Tally("configmaps", "Config Maps", "ConfigMap");
			for (ConfigMap configMap : all) {
				if (references.referencesConfigMap(name(configMap.getMetadata()))) {
					tally.ok();
				}
				else {
					tally.attention(configMap, UNREFERENCED);
				}
			}
			return tally.toKindHealth();
		}
		catch (RuntimeException ex) {
			log.debug("ConfigMap usage failed: {}", ex.getMessage());
			return KindHealth.failed("configmaps", "Config Maps", "ConfigMap", String.valueOf(ex.getMessage()));
		}
	}

	/**
	 * Secrets are listed for their names only — the values are never read and never leave
	 * the server.
	 */
	private KindHealth secrets(String clusterId, String namespace, ConfigReferences references) {
		try {
			var client = this.clusters.require(clusterId).secrets();
			List<Secret> all = ((namespace != null) ? client.inNamespace(namespace) : client.inAnyNamespace()).list()
				.getItems();
			Tally tally = new Tally("secrets", "Secrets", "Secret");
			for (Secret secret : all) {
				if (managedByTheCluster(secret)) {
					tally.ok();
				}
				else if (references.referencesSecret(name(secret.getMetadata()))) {
					tally.ok();
				}
				else {
					tally.attention(secret, UNREFERENCED);
				}
			}
			return tally.toKindHealth();
		}
		catch (RuntimeException ex) {
			log.debug("Secret usage failed: {}", ex.getMessage());
			return KindHealth.failed("secrets", "Secrets", "Secret", String.valueOf(ex.getMessage()));
		}
	}

	/** Secrets whose absence of mounts says nothing about whether they are needed. */
	private boolean managedByTheCluster(Secret secret) {
		String type = secret.getType();
		return SERVICE_ACCOUNT_TOKEN.equals(type) || HELM_RELEASE.equals(type);
	}

	private String name(io.fabric8.kubernetes.api.model.ObjectMeta metadata) {
		return (metadata != null) ? metadata.getName() : "";
	}

}
