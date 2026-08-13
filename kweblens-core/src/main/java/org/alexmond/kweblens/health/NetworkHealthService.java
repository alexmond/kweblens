package org.alexmond.kweblens.health;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.fabric8.kubernetes.api.model.Endpoints;
import io.fabric8.kubernetes.api.model.EndpointSubset;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;

import org.alexmond.kweblens.cluster.ClusterRegistry;
import org.alexmond.kweblens.resource.ResourceService;
import org.alexmond.kweblens.resource.WellKnownKinds;

/**
 * The Network overview's check: <b>Services with nothing answering behind them</b>.
 *
 * <p>
 * This is the classic silent breakage. The Service exists, its DNS name resolves, kubectl
 * shows it as perfectly normal — and every request to it fails, because no pod is backing
 * it. Nothing about the Service object says so; the answer lives in a second object.
 *
 * <p>
 * It distinguishes the two causes, which have different fixes: <b>no matching pods</b>
 * means the selector is wrong or the workload is gone, while <b>pods matched but none
 * ready</b> means the workload is deployed and failing its readiness probe. Both come
 * from one Endpoints list — {@code notReadyAddresses} is exactly that signal — so the
 * whole check costs two list calls, not one per Service.
 *
 * <p>
 * <b>The rule is {@link #verdict}, and both readers go through it</b> (GH#340): the card
 * tallies it, and {@link #contextFor} hands it to the list path so a Service row carries
 * the same state a card counted. There is no second predicate anywhere — the Endpoints
 * join cannot be transcribed into the browser, which is why #337 shipped "the server
 * computes the state and ships it on the row" rather than "one predicate evaluated
 * twice".
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NetworkHealthService {

	private static final String KIND = "Service";

	private final ClusterRegistry clusters;

	private final ResourceService resources;

	/** The kind this judges — the Network half of {@link StatusVocabulary#covers}. */
	public static boolean supports(String kind) {
		return KIND.equals(kind);
	}

	/**
	 * One summary, for Services. Namespace-scoped when given a namespace; otherwise
	 * cluster-wide, which is the same exposure the Workloads overview already has.
	 */
	public List<KindHealth> summarise(String clusterId, String namespace) {
		try {
			List<GenericKubernetesResource> services = this.resources.listRaw(clusterId, WellKnownKinds.SERVICES,
					namespace);
			Map<String, Endpoints> endpoints = endpointsByKey(clusterId, namespace);
			Tally tally = new Tally(WellKnownKinds.SERVICES.id(), WellKnownKinds.SERVICES.label(), KIND);
			for (GenericKubernetesResource service : services) {
				tally.record(verdict(service, endpoints), WorkloadHealth.namespaceOf(service),
						WorkloadHealth.nameOf(service));
			}
			return List.of(tally.toKindHealth());
		}
		catch (RuntimeException ex) {
			log.debug("Network summary failed: {}", ex.getMessage());
			return List.of(KindHealth.failed(WellKnownKinds.SERVICES.id(), WellKnownKinds.SERVICES.label(), KIND,
					String.valueOf(ex.getMessage())));
		}
	}

	/**
	 * A context that judges Service rows, holding the Endpoints collection the verdict
	 * needs.
	 *
	 * <p>
	 * <b>Cost: one extra Endpoints list per Services list request</b> — the same call the
	 * card already makes, over the same scope, and an Endpoints object is a handful of
	 * addresses rather than a pod spec. It is fetched once here, not once per row.
	 */
	public StatusContext contextFor(String clusterId, String namespace) {
		Map<String, Endpoints> endpoints = endpointsByKey(clusterId, namespace);
		return (kind, object) -> supports(kind) ? StatusVocabulary.toState(verdict(object, endpoints)) : null;
	}

	/**
	 * What state this Service is in, given the Endpoints collection.
	 *
	 * <p>
	 * {@code ExternalName} Services are always fine: they are a DNS CNAME with no
	 * endpoints by design, so flagging them would be a false alarm on a correctly
	 * configured object — the failure mode that trains people to ignore the screen.
	 */
	WorkloadHealth.Verdict verdict(GenericKubernetesResource service, Map<String, Endpoints> endpoints) {
		if ("ExternalName".equals(WorkloadHealth.get(service, "spec", "type"))) {
			return WorkloadHealth.Verdict.ok("Serving");
		}
		Endpoints backing = endpoints.get(key(WorkloadHealth.namespaceOf(service), WorkloadHealth.nameOf(service)));
		if (countAddresses(backing, true) > 0) {
			return WorkloadHealth.Verdict.ok("Serving");
		}
		// The two causes are different states, not one: a wrong selector and a failing
		// readiness probe need different fixes, so a card that merges them hides which
		// one
		// you have.
		int notReady = countAddresses(backing, false);
		if (notReady > 0) {
			return WorkloadHealth.Verdict.attention("Not ready",
					notReady + " " + ((notReady == 1) ? "pod" : "pods") + " matched, none ready");
		}
		return WorkloadHealth.Verdict.attention("No endpoints", "no endpoints");
	}

	private int countAddresses(Endpoints endpoints, boolean ready) {
		if (endpoints == null || endpoints.getSubsets() == null) {
			return 0;
		}
		int count = 0;
		for (EndpointSubset subset : endpoints.getSubsets()) {
			var addresses = ready ? subset.getAddresses() : subset.getNotReadyAddresses();
			if (addresses != null) {
				count += addresses.size();
			}
		}
		return count;
	}

	/**
	 * Endpoints indexed by namespace/name — the key a Service is joined on, since an
	 * Endpoints object shares its Service's name.
	 */
	private Map<String, Endpoints> endpointsByKey(String clusterId, String namespace) {
		var client = this.clusters.require(clusterId).endpoints();
		List<Endpoints> all = ((namespace != null) ? client.inNamespace(namespace) : client.inAnyNamespace()).list()
			.getItems();
		Map<String, Endpoints> out = new HashMap<>();
		for (Endpoints endpoints : all) {
			var metadata = endpoints.getMetadata();
			if (metadata != null) {
				out.put(key(metadata.getNamespace(), metadata.getName()), endpoints);
			}
		}
		return out;
	}

	private String key(String namespace, String name) {
		return namespace + "/" + name;
	}

}
