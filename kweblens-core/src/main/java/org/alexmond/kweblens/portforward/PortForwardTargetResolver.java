package org.alexmond.kweblens.portforward;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.ContainerPort;
import io.fabric8.kubernetes.api.model.IntOrString;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServicePort;
import io.fabric8.kubernetes.client.KubernetesClient;

/**
 * Resolves the port a forward must actually bind to inside a pod.
 *
 * <p>
 * A Service's {@code port} is not what the container listens on: the Service maps it to a
 * {@code targetPort}, which may be a number or the <em>name</em> of a container port
 * (legal, and common in Helm charts). {@code kubectl port-forward service/…} performs
 * that translation; forwarding the service port straight through reaches nothing
 * (kweblens issue #289 — podinfo maps {@code 80 -> 9898}). A Pod target has nothing to
 * translate.
 */
final class PortForwardTargetResolver {

	private static final String TCP = "TCP";

	private final KubernetesClient client;

	PortForwardTargetResolver(KubernetesClient client) {
		this.client = client;
	}

	/**
	 * Resolve {@code requestedPort} on {@code kind}/{@code name} to a concrete pod and
	 * pod port.
	 * @throws PortForwardException if the service, the port or a matching pod cannot be
	 * resolved
	 */
	PortForwardTarget resolve(String kind, String namespace, String name, int requestedPort) {
		if (!"service".equals(kind.toLowerCase(Locale.ROOT))) {
			return new PortForwardTarget(namespace, name, requestedPort);
		}
		Service service = this.client.services().inNamespace(namespace).withName(name).get();
		if (service == null || service.getSpec() == null) {
			throw new PortForwardException("Service " + namespace + "/" + name + " not found");
		}
		ServicePort servicePort = matchingPort(service, namespace, name, requestedPort);
		Pod pod = selectPod(service, namespace, name);
		int podPort = targetPort(servicePort, pod, namespace, name);
		return new PortForwardTarget(pod.getMetadata().getNamespace(), pod.getMetadata().getName(), podPort);
	}

	private ServicePort matchingPort(Service service, String namespace, String name, int requestedPort) {
		List<ServicePort> ports = (service.getSpec().getPorts() != null) ? service.getSpec().getPorts() : List.of();
		return ports.stream()
			.filter((port) -> port.getPort() != null && port.getPort() == requestedPort)
			.findFirst()
			.orElseThrow(() -> new PortForwardException("Service " + namespace + "/" + name + " has no port "
					+ requestedPort + " (it exposes " + describe(ports) + ")"));
	}

	private String describe(List<ServicePort> ports) {
		if (ports.isEmpty()) {
			return "no ports";
		}
		return ports.stream().map((port) -> String.valueOf(port.getPort())).reduce((a, b) -> a + ", " + b).orElse("");
	}

	/**
	 * The {@code targetPort}: absent means "same as {@code port}", a number is used
	 * as-is, and a name is looked up in the selected pod's container ports.
	 */
	private int targetPort(ServicePort servicePort, Pod pod, String namespace, String name) {
		IntOrString target = servicePort.getTargetPort();
		if (target == null) {
			return servicePort.getPort();
		}
		if (target.getIntVal() != null) {
			return target.getIntVal();
		}
		String named = target.getStrVal();
		if (named == null || named.isBlank()) {
			return servicePort.getPort();
		}
		Integer numeric = parseNumber(named);
		if (numeric != null) {
			return numeric;
		}
		return namedPort(named, servicePort, pod, namespace, name);
	}

	private Integer parseNumber(String value) {
		try {
			return Integer.valueOf(value);
		}
		catch (NumberFormatException ex) {
			return null;
		}
	}

	private int namedPort(String named, ServicePort servicePort, Pod pod, String namespace, String name) {
		List<Container> containers = (pod.getSpec() != null && pod.getSpec().getContainers() != null)
				? pod.getSpec().getContainers() : List.of();
		String wanted = protocol(servicePort.getProtocol());
		for (Container container : containers) {
			List<ContainerPort> ports = (container.getPorts() != null) ? container.getPorts()
					: List.<ContainerPort>of();
			for (ContainerPort port : ports) {
				if (named.equals(port.getName()) && wanted.equals(protocol(port.getProtocol()))
						&& port.getContainerPort() != null) {
					return port.getContainerPort();
				}
			}
		}
		throw new PortForwardException("Service " + namespace + "/" + name + " targets the named port '" + named
				+ "', which pod " + pod.getMetadata().getName() + " does not declare");
	}

	private String protocol(String protocol) {
		return (protocol == null || protocol.isBlank()) ? TCP : protocol.toUpperCase(Locale.ROOT);
	}

	/**
	 * The pod a forward lands on, chosen from the Service's selector — a running pod in
	 * preference to any other, like {@code kubectl} does.
	 */
	private Pod selectPod(Service service, String namespace, String name) {
		Map<String, String> selector = service.getSpec().getSelector();
		if (selector == null || selector.isEmpty()) {
			throw new PortForwardException(
					"Service " + namespace + "/" + name + " has no selector, so there is no pod to forward to");
		}
		List<Pod> pods = this.client.pods().inNamespace(namespace).withLabels(selector).list().getItems();
		return pods.stream()
			.filter(PortForwardTargetResolver::isRunning)
			.findFirst()
			.or(() -> pods.stream().findFirst())
			.orElseThrow(() -> new PortForwardException(
					"Service " + namespace + "/" + name + " selects no pods, so there is nothing to forward to"));
	}

	private static boolean isRunning(Pod pod) {
		return pod.getStatus() != null && "Running".equals(pod.getStatus().getPhase());
	}

}
