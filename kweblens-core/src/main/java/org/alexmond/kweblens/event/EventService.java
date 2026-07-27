package org.alexmond.kweblens.event;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;

import org.alexmond.kweblens.resource.ResourceDescriptor;
import org.alexmond.kweblens.resource.ResourceService;
import org.alexmond.kweblens.resource.ResourceSummary;
import org.alexmond.kweblens.resource.WellKnownKinds;

/**
 * Reads core Kubernetes events and projects them into {@link EventSummary} rows, newest
 * first. Events can be filtered to a namespace and/or a single involved object
 * (Kind/name) — the latter powers the "Events" tab on a resource detail.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EventService {

	private static final ResourceDescriptor EVENTS = WellKnownKinds.EVENTS;

	private final ResourceService resources;

	/** All events (optionally scoped to a namespace), newest first. */
	public List<EventSummary> list(String clusterId, String namespace) {
		return listFor(clusterId, namespace, null, null);
	}

	/** Events for one involved object (by kind + name), optionally within a namespace. */
	public List<EventSummary> listForObject(String clusterId, String namespace, String kind, String name) {
		return listFor(clusterId, namespace, kind, name);
	}

	private List<EventSummary> listFor(String clusterId, String namespace, String kind, String name) {
		return resources.listRaw(clusterId, EVENTS, namespace)
			.stream()
			.filter((e) -> matchesObject(e, kind, name))
			.sorted(Comparator.comparing((GenericKubernetesResource e) -> lastTimestamp(e),
					Comparator.nullsLast(Comparator.reverseOrder())))
			.map(this::toSummary)
			.toList();
	}

	private boolean matchesObject(GenericKubernetesResource event, String kind, String name) {
		if (kind == null && name == null) {
			return true;
		}
		Map<String, Object> involved = involvedObject(event);
		boolean kindOk = (kind == null) || kind.equals(str(involved.get("kind")));
		boolean nameOk = (name == null) || name.equals(str(involved.get("name")));
		return kindOk && nameOk;
	}

	private EventSummary toSummary(GenericKubernetesResource event) {
		Map<String, Object> props = event.getAdditionalProperties();
		Map<String, Object> involved = involvedObject(event);
		String object = str(involved.get("kind")) + "/" + str(involved.get("name"));
		String namespace = (event.getMetadata() != null) ? event.getMetadata().getNamespace() : null;
		String age = ResourceSummary.age(lastTimestamp(event), Instant.now());
		return new EventSummary(str(props.get("type")), str(props.get("reason")), object, namespace,
				str(props.get("message")), age);
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> involvedObject(GenericKubernetesResource event) {
		Object involved = event.getAdditionalProperties().get("involvedObject");
		return (involved instanceof Map) ? (Map<String, Object>) involved : Map.of();
	}

	private Instant lastTimestamp(GenericKubernetesResource event) {
		Map<String, Object> props = event.getAdditionalProperties();
		Object ts = (props.get("lastTimestamp") != null) ? props.get("lastTimestamp") : props.get("eventTime");
		if (ts == null && event.getMetadata() != null) {
			ts = event.getMetadata().getCreationTimestamp();
		}
		return parse(str(ts));
	}

	private Instant parse(String timestamp) {
		if (timestamp == null || timestamp.isBlank()) {
			return null;
		}
		try {
			return Instant.parse(timestamp);
		}
		catch (DateTimeParseException ex) {
			log.debug("Unparseable event timestamp '{}'", timestamp);
			return null;
		}
	}

	private String str(Object value) {
		return (value != null) ? value.toString() : null;
	}

}
