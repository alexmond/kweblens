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
 * Relations are always present as an object, and each either carries items or reports a
 * failure — never a bare empty list on failure. "There are none" is a factual claim about
 * the cluster, and asserting it wrongly sends the reader after the wrong problem. A
 * failure always carries {@code error}, and a refusal carries {@code error} <em>and</em>
 * {@code notPermitted: true} — the two are not alternatives, so a client branches on
 * {@code notPermitted} FIRST and only then on {@code error}. See {@link Relation}.
 *
 * <p>
 * The {@code {namespace}} segment is {@value ResourceActionApiController#NO_NAMESPACE}
 * for a cluster-scoped kind: the route has no cluster-scoped form and an empty segment
 * does not match the mapping. {@code ResourceService.getRaw} ignores the namespace for
 * such a kind anyway, but mapping the sentinel here makes the URL contract the same one
 * the action routes use rather than something that happens to work (#313).
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
		String ns = namespaceOf(namespace);
		GenericKubernetesResource object = this.resources.getRaw(clusterId, descriptor, ns, name);
		if (object == null) {
			throw new IllegalArgumentException("No such " + descriptor.kind() + ": " + ref(ns, name));
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

	/**
	 * The placeholder segment back to "no namespace"; anything else is passed through.
	 * The sentinel itself is {@link ResourceActionApiController#NO_NAMESPACE} — one
	 * constant for the whole URL contract, not a second spelling of it.
	 */
	private static String namespaceOf(String namespace) {
		return ResourceActionApiController.NO_NAMESPACE.equals(namespace) ? "" : namespace;
	}

	/** {@code ns/name}, or just the name when the kind is cluster-scoped. */
	private static String ref(String namespace, String name) {
		return namespace.isEmpty() ? name : namespace + "/" + name;
	}

}
