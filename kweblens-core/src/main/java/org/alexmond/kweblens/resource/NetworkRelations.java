package org.alexmond.kweblens.resource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.networking.v1.HTTPIngressPath;
import io.fabric8.kubernetes.api.model.networking.v1.Ingress;
import io.fabric8.kubernetes.api.model.networking.v1.IngressBackend;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.alexmond.kweblens.cluster.ClusterRegistry;

/**
 * How traffic reaches an object: what backs a Service, what a selector selects, and what
 * routes to it from outside.
 */
@Slf4j
@RequiredArgsConstructor
class NetworkRelations {

	private final ClusterRegistry clusters;

	/**
	 * The Endpoints backing a Service, which is how you tell "no pods match my selector"
	 * from "pods match but none are ready" — the single most common Service
	 * misconfiguration, and invisible from the Service object itself.
	 *
	 * <p>
	 * Cost: one GET.
	 */
	Relation endpoints(String clusterId, String namespace, String serviceName) {
		try {
			var endpoints = this.clusters.require(clusterId)
				.endpoints()
				.inNamespace(namespace)
				.withName(serviceName)
				.get();
			if (endpoints == null) {
				// No Endpoints object at all is itself the answer: nothing is backing it.
				return Relation.of(List.of());
			}
			return RelationSupport.bound(List.of(endpoints));
		}
		catch (RuntimeException ex) {
			log.debug("Endpoints lookup failed for {}/{}: {}", namespace, serviceName, ex.getMessage());
			return Relation.from(ex);
		}
	}

	/**
	 * Pods matched by the object's selector — for a Service, what it actually routes to;
	 * for a workload, which replicas it owns. Uses a server-side label selector, so the
	 * API server does the filtering rather than kweblens listing the namespace.
	 *
	 * <p>
	 * Cost: one label-selected LIST.
	 */
	Relation selectedPods(String clusterId, String namespace, GenericKubernetesResource object) {
		Map<String, String> selector = RelationSupport.selectorOf(object);
		if (selector.isEmpty()) {
			// A selector-less object matches nothing; saying so beats an unexplained
			// blank.
			return Relation.of(List.of());
		}
		try {
			List<Pod> pods = this.clusters.require(clusterId)
				.pods()
				.inNamespace(namespace)
				.withLabels(selector)
				.list()
				.getItems();
			return RelationSupport.bound(pods);
		}
		catch (RuntimeException ex) {
			log.debug("Selector pod lookup failed in {}: {}", namespace, ex.getMessage());
			return Relation.from(ex);
		}
	}

	/**
	 * The Ingresses that send traffic to this Service.
	 *
	 * <p>
	 * "How is this reachable from outside" is answerable from the Ingress but not from
	 * the Service, and the failure it catches is specific: an Ingress naming a service
	 * port that does not exist, or naming a Service that was renamed, routes 503 while
	 * both objects look healthy on their own.
	 *
	 * <p>
	 * Cost: one namespace LIST of Ingresses, filtered locally — Ingress has no field
	 * selector for its backends. Ingresses are few per namespace (typically single
	 * digits), which is why this reverse join is affordable where a cluster-wide one
	 * would not be. Only {@code networking.k8s.io/v1} is read; a cluster still on the
	 * removed {@code v1beta1} returns nothing rather than failing, and Gateway API
	 * {@code HTTPRoute} is deliberately not attempted here (see {@code RelationService}).
	 */
	Relation routedBy(String clusterId, String namespace, String serviceName) {
		try {
			List<Ingress> ingresses = this.clusters.require(clusterId)
				.network()
				.v1()
				.ingresses()
				.inNamespace(namespace)
				.list()
				.getItems();
			List<Object> matches = new ArrayList<>();
			for (Ingress ingress : ingresses) {
				if (routesTo(ingress, serviceName)) {
					matches.add(ingress);
				}
			}
			return RelationSupport.bound(matches);
		}
		catch (RuntimeException ex) {
			log.debug("Ingress scan failed in {}: {}", namespace, ex.getMessage());
			return Relation.from(ex);
		}
	}

	private boolean routesTo(Ingress ingress, String serviceName) {
		if (ingress.getSpec() == null) {
			return false;
		}
		if (backendNames(ingress.getSpec().getDefaultBackend(), serviceName)) {
			return true;
		}
		if (ingress.getSpec().getRules() == null) {
			return false;
		}
		for (var rule : ingress.getSpec().getRules()) {
			if (rule.getHttp() == null || rule.getHttp().getPaths() == null) {
				continue;
			}
			for (HTTPIngressPath path : rule.getHttp().getPaths()) {
				if (backendNames(path.getBackend(), serviceName)) {
					return true;
				}
			}
		}
		return false;
	}

	private boolean backendNames(IngressBackend backend, String serviceName) {
		return backend != null && backend.getService() != null && serviceName.equals(backend.getService().getName());
	}

}
