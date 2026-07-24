package org.alexmond.kweblens.web.api;

import java.util.List;

import lombok.RequiredArgsConstructor;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import org.alexmond.kweblens.event.EventService;
import org.alexmond.kweblens.event.EventSummary;

/**
 * Read-only JSON API over cluster events, newest first. Optionally scoped to a namespace
 * and/or a single involved object (by kind + name).
 */
@RestController
@RequestMapping("/api/v1/clusters/{clusterId}/events")
@RequiredArgsConstructor
public class EventApiController {

	private final EventService events;

	@GetMapping
	public List<EventSummary> events(@PathVariable String clusterId, @RequestParam(required = false) String namespace,
			@RequestParam(required = false) String kind, @RequestParam(required = false) String name) {
		if (kind != null || name != null) {
			return events.listForObject(clusterId, namespace, kind, name);
		}
		return events.list(clusterId, namespace);
	}

}
