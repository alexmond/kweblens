package org.alexmond.kweblens.health;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.fabric8.kubernetes.api.model.Endpoints;
import io.fabric8.kubernetes.api.model.EndpointSubset;
import io.fabric8.kubernetes.api.model.Service;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.alexmond.kweblens.cluster.ClusterRegistry;

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
 */
@Slf4j
@org.springframework.stereotype.Service
@RequiredArgsConstructor
public class NetworkHealthService {

	private static final String KIND = "Service";

	private final ClusterRegistry clusters;

	/**
	 * One summary, for Services. Namespace-scoped when given a namespace; otherwise
	 * cluster-wide, which is the same exposure the Workloads overview already has.
	 */
	public List<KindHealth> summarise(String clusterId, String namespace) {
		try {
			List<Service> services = services(clusterId, namespace);
			Map<String, Endpoints> endpoints = endpointsByKey(clusterId, namespace);
			Tally tally = new Tally("services", "Services", KIND);
			for (Service service : services) {
				String reason = problem(service, endpoints);
				if (reason == null) {
					tally.ok("Serving", StateCount.OK);
				}
				else {
					// The two causes are different states, not one: a wrong selector and
					// a
					// failing readiness probe need different fixes, so a card that merges
					// them hides which one you have.
					String label = "no endpoints".equals(reason) ? "No endpoints" : "Not ready";
					tally.attention(service, reason, label, StateCount.ERR);
				}
			}
			return List.of(tally.toKindHealth());
		}
		catch (RuntimeException ex) {
			log.debug("Network summary failed: {}", ex.getMessage());
			return List.of(KindHealth.failed("services", "Services", KIND, String.valueOf(ex.getMessage())));
		}
	}

	/**
	 * Why this Service has nothing behind it, or {@code null} when it is fine.
	 *
	 * <p>
	 * {@code ExternalName} Services are always fine: they are a DNS CNAME with no
	 * endpoints by design, so flagging them would be a false alarm on a correctly
	 * configured object — the failure mode that trains people to ignore the screen.
	 */
	private String problem(Service service, Map<String, Endpoints> endpoints) {
		if (service.getSpec() != null && "ExternalName".equals(service.getSpec().getType())) {
			return null;
		}
		Endpoints backing = endpoints.get(key(service));
		int ready = countAddresses(backing, true);
		if (ready > 0) {
			return null;
		}
		int notReady = countAddresses(backing, false);
		if (notReady > 0) {
			// Deployed but failing readiness — a different fix from a broken selector.
			return notReady + " " + ((notReady == 1) ? "pod" : "pods") + " matched, none ready";
		}
		return "no endpoints";
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

	private List<Service> services(String clusterId, String namespace) {
		var client = this.clusters.require(clusterId).services();
		return ((namespace != null) ? client.inNamespace(namespace) : client.inAnyNamespace()).list().getItems();
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
			out.put(key(endpoints), endpoints);
		}
		return out;
	}

	private String key(io.fabric8.kubernetes.api.model.HasMetadata object) {
		if (object.getMetadata() == null) {
			return "";
		}
		return object.getMetadata().getNamespace() + "/" + object.getMetadata().getName();
	}

}
