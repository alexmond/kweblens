package org.alexmond.kweblens.health;

import java.util.ArrayList;
import java.util.List;

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;

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
			Tally tally = new Tally(descriptor.id(), descriptor.label(), kind);
			for (GenericKubernetesResource o : objects) {
				// Through the seam, not straight to WorkloadHealth: this is the same
				// call StatusVocabulary.state puts on the list row, which is what makes
				// the card's number and the rows a status: term selects one set rather
				// than two that agree today (GH#337).
				tally.record(StatusVocabulary.verdict(kind, o), WorkloadHealth.namespaceOf(o),
						WorkloadHealth.nameOf(o));
			}
			return tally.toKindHealth();
		}
		catch (RuntimeException ex) {
			log.debug("Health summary failed for '{}': {}", descriptor.id(), ex.getMessage());
			return KindHealth.failed(descriptor.id(), descriptor.label(), kind, String.valueOf(ex.getMessage()));
		}
	}

}
