package org.alexmond.kweblens.health;

import java.util.ArrayList;
import java.util.List;

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.client.KubernetesClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;

import org.alexmond.kweblens.cluster.ClusterRegistry;
import org.alexmond.kweblens.resource.ResourceDescriptor;
import org.alexmond.kweblens.resource.ResourceService;
import org.alexmond.kweblens.resource.WellKnownKinds;

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
 *
 * <p>
 * <b>This is the verdict that costs the most to put on a list row</b> (GH#340). It is not
 * a property of the ConfigMap at all — it is a property of everything else in the
 * namespace — so {@link #contextFor} runs the same scan the card runs, <i>once</i> per
 * list request, and every row is then a set lookup. What that costs is written on that
 * method.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConfigUsageService {

	private static final String CONFIG_MAP = "ConfigMap";

	private static final String SECRET = "Secret";

	private static final String SERVICE_ACCOUNT_TOKEN = "kubernetes.io/service-account-token";

	private static final String HELM_RELEASE = "helm.sh/release.v1";

	private static final String UNREFERENCED = "not referenced in this namespace";

	private final ClusterRegistry clusters;

	private final ResourceService resources;

	/** The kinds this judges — the Config half of {@link StatusVocabulary#covers}. */
	public static boolean supports(String kind) {
		return CONFIG_MAP.equals(kind) || SECRET.equals(kind);
	}

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
			out.add(failed(WellKnownKinds.CONFIG_MAPS, message));
			out.add(failed(WellKnownKinds.SECRETS, message));
			return out;
		}
		out.add(tally(clusterId, namespace, WellKnownKinds.CONFIG_MAPS, references));
		out.add(tally(clusterId, namespace, WellKnownKinds.SECRETS, references));
		return out;
	}

	/**
	 * A context that judges ConfigMap and Secret rows, holding the namespace's reference
	 * scan.
	 *
	 * <p>
	 * <b>Cost: three extra list calls per ConfigMaps or Secrets list request</b> — pods,
	 * service accounts and ingresses over the same scope — of which the pod list is the
	 * expensive one, and it is cluster-wide when no namespace is selected. That is
	 * precisely what the Config overview already spends to draw the card, spent again to
	 * make the card's numbers clickable; it is <b>once per request</b>, never per row.
	 * Should any of it fail, {@link StatusContexts} falls back to no context and the rows
	 * carry no state — an unfiltered list, not a wrong one.
	 */
	public StatusContext contextFor(String clusterId, String namespace) {
		ConfigReferences references = scan(clusterId, namespace);
		return (kind, object) -> supports(kind) ? StatusVocabulary.toState(verdict(kind, object, references)) : null;
	}

	/**
	 * Whether anything in the scanned scope names this object.
	 *
	 * <p>
	 * Secrets the cluster creates and owns are neither referenced nor a finding: nothing
	 * mounting a service-account token or a Helm release record says nothing about
	 * whether it is needed, so they get their own state instead of being counted as
	 * unused. Being unreferenced is advisory — amber, not red — because on a real cluster
	 * it is routinely true of config that is entirely in use.
	 */
	WorkloadHealth.Verdict verdict(String kind, GenericKubernetesResource object, ConfigReferences references) {
		String name = WorkloadHealth.nameOf(object);
		if (SECRET.equals(kind)) {
			if (managedByTheCluster(object)) {
				return WorkloadHealth.Verdict.idle("Cluster-managed");
			}
			return references.referencesSecret(name) ? WorkloadHealth.Verdict.ok("Referenced")
					: WorkloadHealth.Verdict.attentionSoft("Not referenced", UNREFERENCED);
		}
		return references.referencesConfigMap(name) ? WorkloadHealth.Verdict.ok("Referenced")
				: WorkloadHealth.Verdict.attentionSoft("Not referenced", UNREFERENCED);
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

	/**
	 * One kind's tally. Secrets are listed for their names only — the values are never
	 * read here, and the generic list path is the same one the rows travel, so the two
	 * sides count the same objects as well as judging them the same way.
	 */
	private KindHealth tally(String clusterId, String namespace, ResourceDescriptor descriptor,
			ConfigReferences references) {
		try {
			List<GenericKubernetesResource> all = this.resources.listRaw(clusterId, descriptor, namespace);
			Tally tally = new Tally(descriptor.id(), descriptor.label(), descriptor.kind());
			for (GenericKubernetesResource object : all) {
				tally.record(verdict(descriptor.kind(), object, references), WorkloadHealth.namespaceOf(object),
						WorkloadHealth.nameOf(object));
			}
			return tally.toKindHealth();
		}
		catch (RuntimeException ex) {
			log.debug("Config usage failed for '{}': {}", descriptor.id(), ex.getMessage());
			return failed(descriptor, String.valueOf(ex.getMessage()));
		}
	}

	private KindHealth failed(ResourceDescriptor descriptor, String message) {
		return KindHealth.failed(descriptor.id(), descriptor.label(), descriptor.kind(), message);
	}

	/** Secrets whose absence of mounts says nothing about whether they are needed. */
	private boolean managedByTheCluster(GenericKubernetesResource secret) {
		Object type = WorkloadHealth.get(secret, "type");
		return SERVICE_ACCOUNT_TOKEN.equals(type) || HELM_RELEASE.equals(type);
	}

}
