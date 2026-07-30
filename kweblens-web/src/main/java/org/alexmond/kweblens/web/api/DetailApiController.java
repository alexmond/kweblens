package org.alexmond.kweblens.web.api;

import java.util.LinkedHashMap;
import java.util.Map;

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.client.utils.Serialization;
import lombok.RequiredArgsConstructor;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import org.alexmond.kweblens.resource.Relation;
import org.alexmond.kweblens.resource.RelationService;
import org.alexmond.kweblens.resource.ResourceDescriptor;
import org.alexmond.kweblens.resource.ResourceService;
import org.alexmond.kweblens.web.nav.ClusterNavService;

/**
 * One object plus its resolved RELATIONS, in a single response.
 *
 * <p>
 * The alternative — letting the browser fetch each relation itself — was rejected: it
 * means N extra requests per drawer open, and every client (SPA, a future TUI, the agent
 * tool surface) reimplementing the same joins. Doing them server-side keeps one
 * implementation and lets the server bound the cost.
 *
 * <p>
 * Relations are always present as an object, and each carries items OR an error OR
 * {@code notPermitted} — never a bare empty list on failure. "There are none" is a
 * factual claim about the cluster, and asserting it wrongly sends the reader after the
 * wrong problem.
 */
@RestController
@RequiredArgsConstructor
public class DetailApiController {

	private final ResourceService resources;

	private final RelationService relations;

	private final ClusterNavService clusterNav;

	@GetMapping(value = "/api/v1/clusters/{clusterId}/detail/{resourceId}/{namespace}/{name}",
			produces = MediaType.APPLICATION_JSON_VALUE)
	public String detail(@PathVariable String clusterId, @PathVariable String resourceId,
			@PathVariable String namespace, @PathVariable String name) {
		ResourceDescriptor descriptor = this.clusterNav.find(clusterId, resourceId)
			.orElseThrow(() -> new UnknownResourceException(resourceId));
		GenericKubernetesResource object = this.resources.getRaw(clusterId, descriptor, namespace, name);
		if (object == null) {
			throw new IllegalArgumentException("No such " + descriptor.kind() + ": " + namespace + "/" + name);
		}
		Map<String, Relation> resolved = this.relations.relationsFor(clusterId, descriptor, object);
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("object", object);
		body.put("relations", resolved);
		// fabric8's serializer, so the object matches the cluster's own representation
		// exactly
		// (apiVersion/kind/metadata/spec/status) rather than Jackson's view of the model
		// types.
		return Serialization.asJson(body);
	}

}
