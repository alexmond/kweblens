package org.alexmond.kweblens.web.sim;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import io.fabric8.kubernetes.api.model.EndpointAddress;
import io.fabric8.kubernetes.api.model.EndpointAddressBuilder;
import io.fabric8.kubernetes.api.model.EndpointSubsetBuilder;
import io.fabric8.kubernetes.api.model.Endpoints;
import io.fabric8.kubernetes.api.model.EndpointsBuilder;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServiceBuilder;
import io.fabric8.kubernetes.api.model.networking.v1.Ingress;
import io.fabric8.kubernetes.api.model.networking.v1.IngressBuilder;

/**
 * Services, their Endpoints, and Ingresses.
 *
 * <p>
 * Services were missing from the simulator entirely, which meant the Network overview's
 * one check — <b>a Service with nothing answering behind it</b>, the classic silent
 * breakage — could not be exercised at all without a real cluster. It can now: one
 * Service in fourteen gets no Endpoints object (a wrong selector, or the workload is
 * gone) and one in twenty gets Endpoints holding only {@code notReadyAddresses}
 * (deployed, failing its readiness probe). Those are different bugs with different fixes,
 * and {@code NetworkHealthService} distinguishes them, so the rig has to produce both.
 *
 * <p>
 * The Ingress keeps the TLS host the contrast checker's scene depends on — the drawer's
 * two chip styles are only renderable against an object that has one — and gains the
 * annotations, ingressClassName and load-balancer status a real one carries.
 */
final class SimNetwork {

	private SimNetwork() {
	}

	/** No Endpoints at all: the selector matches nothing. */
	static boolean hasNoEndpoints(int index) {
		return SimPayloads.roll("svc-health", index) < 7;
	}

	/** Endpoints exist but nothing in them is ready. */
	static boolean hasUnreadyEndpoints(int index) {
		int roll = SimPayloads.roll("svc-health", index);
		return roll >= 7 && roll < 12;
	}

	static Service service(int index, String namespace) {
		String name = "sim-svc-" + index;
		Map<String, String> labels = SimMeta.appLabels(index, "web");
		Map<String, String> annotations = SimMeta.commonAnnotations(index, "Service", namespace);
		ObjectMeta meta = SimMeta.meta("Service", index, name, namespace, labels, annotations);
		// Two thirds expose a metrics port as well as http — the variance a real
		// cluster's
		// Services have, and the reason theirs range 1.6-2.7 KB rather than all being one
		// size.
		boolean metrics = new SimRandom("svc-ports", index).chance(65);
		meta.setManagedFields(List.of(SimFields.entry("helm", "Update", SimMeta.created("mf", index),
				servicePaths(labels, annotations, metrics))));
		ServiceBuilder service = new ServiceBuilder().withMetadata(meta)
			.withNewSpec()
			.withType("ClusterIP")
			.withClusterIP(clusterIp(index))
			.withClusterIPs(clusterIp(index))
			.withIpFamilies("IPv4")
			.withIpFamilyPolicy("SingleStack")
			.withInternalTrafficPolicy("Cluster")
			.withSessionAffinity("None")
			.addToSelector("app", "sim")
			.addToSelector("app.kubernetes.io/component", "web")
			.addNewPort()
			.withName("http")
			.withPort(80)
			.withProtocol("TCP")
			.withNewTargetPort(8080)
			.endPort()
			.endSpec()
			.withNewStatus()
			.withNewLoadBalancer()
			.endLoadBalancer()
			.endStatus();
		if (metrics) {
			service.editSpec()
				.addNewPort()
				.withName("metrics")
				.withPort(9102)
				.withProtocol("TCP")
				.withNewTargetPort(9102)
				.endPort()
				.endSpec();
		}
		return service.build();
	}

	/**
	 * The Endpoints for a Service — or a not-ready-only variant. Returns {@code null}
	 * when this Service is one of the broken ones, because "no Endpoints object" is
	 * exactly what the check looks for and an empty one is a different (rarer) shape.
	 */
	static Endpoints endpoints(int index, String namespace, int pods) {
		if (hasNoEndpoints(index)) {
			return null;
		}
		Map<String, String> labels = SimMeta.appLabels(index, "web");
		ObjectMeta meta = SimMeta.meta("Endpoints", index, "sim-svc-" + index, namespace, labels,
				Map.of("endpoints.kubernetes.io/last-change-trigger-time", SimMeta.created("ep", index)));
		List<EndpointAddress> addresses = new ArrayList<>();
		for (int i = 0; i < Math.min(3, Math.max(1, pods)); i++) {
			addresses.add(address((index + i) % Math.max(1, pods), namespace));
		}
		EndpointSubsetBuilder subset = new EndpointSubsetBuilder().addNewPort()
			.withName("http")
			.withPort(8080)
			.withProtocol("TCP")
			.endPort();
		// "Deployed but not ready" is a different bug from "nothing matches the
		// selector",
		// and NetworkHealthService tells them apart by exactly this field.
		if (hasUnreadyEndpoints(index)) {
			subset.withNotReadyAddresses(addresses);
		}
		else {
			subset.withAddresses(addresses);
		}
		return new EndpointsBuilder().withMetadata(meta).withSubsets(subset.build()).build();
	}

	private static EndpointAddress address(int pod, String namespace) {
		return new EndpointAddressBuilder().withIp(SimPods.podIp(pod))
			.withNodeName(SimNodes.nodeName(pod % 3))
			.withNewTargetRef()
			.withKind("Pod")
			.withName("sim-pod-" + pod)
			.withNamespace(namespace)
			.withUid(SimMeta.uid("Pod", pod))
			.endTargetRef()
			.build();
	}

	static Ingress ingress(int index, String namespace) {
		String host = "sim-" + index + ".example.test";
		Map<String, String> labels = SimMeta.appLabels(index, "web");
		Map<String, String> annotations = SimMeta.commonAnnotations(index, "Ingress", namespace);
		annotations.put("cert-manager.io/cluster-issuer", "letsencrypt");
		annotations.put("nginx.ingress.kubernetes.io/proxy-body-size", "32m");
		annotations.put("nginx.ingress.kubernetes.io/ssl-redirect", "true");
		ObjectMeta meta = SimMeta.meta("Ingress", index, "sim-ingress-" + index, namespace, labels, annotations);
		meta.setManagedFields(List.of(SimFields.entry("helm", "Update", SimMeta.created("mf", index),
				SimFields.metadataPaths(labels, annotations))));
		return new IngressBuilder().withMetadata(meta)
			.withNewSpec()
			.withIngressClassName("nginx")
			.addNewTl()
			.withHosts(host)
			.withSecretName("sim-tls-" + index)
			.endTl()
			.addNewRule()
			.withHost(host)
			.withNewHttp()
			.addNewPath()
			.withPath("/")
			.withPathType("Prefix")
			.withNewBackend()
			.withNewService()
			.withName("sim-svc-" + index)
			.withNewPort()
			.withNumber(80)
			.endPort()
			.endService()
			.endBackend()
			.endPath()
			.endHttp()
			.endRule()
			.endSpec()
			.withNewStatus()
			.withNewLoadBalancer()
			.addNewIngress()
			.withIp(SimPods.hostIp(index))
			.endIngress()
			.endLoadBalancer()
			.endStatus()
			.build();
	}

	private static List<String> servicePaths(Map<String, String> labels, Map<String, String> annotations,
			boolean metrics) {
		List<String> paths = new ArrayList<>(SimFields.metadataPaths(labels, annotations));
		paths.addAll(List.of("spec|internalTrafficPolicy", "spec|ipFamilyPolicy", "spec|ports|.", "spec|selector",
				"spec|sessionAffinity", "spec|type"));
		paths.addAll(portPaths(80));
		if (metrics) {
			paths.addAll(portPaths(9102));
		}
		return paths;
	}

	private static List<String> portPaths(int port) {
		String base = "spec|ports|k:{\"port\":" + port + ",\"protocol\":\"TCP\"}";
		return List.of(base + "|.", base + "|name", base + "|port", base + "|protocol", base + "|targetPort");
	}

	private static String clusterIp(int index) {
		return "10.43." + (index % 250) + '.' + ((index * 3) % 250);
	}

}
