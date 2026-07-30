package org.alexmond.kweblens.health;

import java.util.ArrayList;
import java.util.List;

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;

import org.alexmond.kweblens.health.KindHealth.UnhealthyItem;
import org.alexmond.kweblens.resource.ResourceDescriptor;
import org.alexmond.kweblens.resource.ResourceService;

/**
 * Per-kind health summaries for the Workloads overview.
 *
 * <p>
 * <b>Why this is server-side.</b> The overview previously fetched every object of seven
 * kinds <em>to the browser</em> in order to produce seven numbers — on a large cluster
 * that is the "~400 MB pod LIST" failure mode, and it duplicated work the nav's
 * {@code /counts} already did. Computing here sends a small summary instead.
 *
 * <p>
 * It does <em>not</em> avoid the listing itself: Kubernetes has no "give me the unhealthy
 * ones" query, so the objects must be examined somewhere. The win is doing it once, close
 * to the API server, rather than once per open browser tab — and it means the same
 * summary can serve a TUI and the agent without either reimplementing the predicates.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HealthService {

	/**
	 * Cap on named unhealthy objects per kind. A namespace-wide outage should produce a
	 * usable list, not a thousand rows — and the cap is reported, never silently applied.
	 */
	private static final int MAX_NAMED = 25;

	private final ResourceService resources;

	/**
	 * Summarise each descriptor. A kind that cannot be listed yields an {@code error}
	 * rather than a zero: on a health screen, "nothing is wrong" and "I could not check"
	 * must never look the same.
	 */
	public List<KindHealth> summarise(String clusterId, List<ResourceDescriptor> descriptors, String namespace) {
		List<KindHealth> out = new ArrayList<>();
		for (ResourceDescriptor descriptor : descriptors) {
			out.add(summariseKind(clusterId, descriptor, namespace));
		}
		return out;
	}

	private KindHealth summariseKind(String clusterId, ResourceDescriptor descriptor, String namespace) {
		String kind = descriptor.kind();
		try {
			List<GenericKubernetesResource> objects = this.resources.listRaw(clusterId, descriptor, namespace);
			int ok = 0;
			int attention = 0;
			int suspended = 0;
			List<UnhealthyItem> named = new ArrayList<>();
			for (GenericKubernetesResource o : objects) {
				WorkloadHealth.Verdict verdict = WorkloadHealth.verdict(kind, o);
				switch (verdict.state()) {
					case ATTENTION -> {
						attention++;
						if (named.size() < MAX_NAMED) {
							named.add(item(kind, o, verdict.reason()));
						}
					}
					case SUSPENDED -> suspended++;
					default -> ok++;
				}
			}
			return new KindHealth(descriptor.id(), descriptor.label(), kind, objects.size(), ok, attention, suspended,
					List.copyOf(named), attention > named.size(), null);
		}
		catch (RuntimeException ex) {
			log.debug("Health summary failed for '{}': {}", descriptor.id(), ex.getMessage());
			return KindHealth.failed(descriptor.id(), descriptor.label(), kind, String.valueOf(ex.getMessage()));
		}
	}

	private UnhealthyItem item(String kind, GenericKubernetesResource o, String reason) {
		String namespace = (o.getMetadata() != null) ? o.getMetadata().getNamespace() : null;
		String name = (o.getMetadata() != null) ? o.getMetadata().getName() : "";
		return new UnhealthyItem(kind, namespace, name, reason);
	}

}
