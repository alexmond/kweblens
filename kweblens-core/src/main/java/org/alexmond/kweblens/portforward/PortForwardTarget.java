package org.alexmond.kweblens.portforward;

/**
 * Where a forward actually lands, after the Service-to-Pod translation that
 * {@code kubectl port-forward service/…} performs.
 *
 * <p>
 * For a Pod target there is nothing to translate: {@code podPort} equals the requested
 * port and {@code podName} is the pod that was asked for. For a Service, {@code podPort}
 * is the {@code targetPort} of the matching {@code ServicePort} — resolved against the
 * selected pod's container ports when it is a <em>named</em> port — and {@code podName}
 * is the pod chosen from the Service's selector.
 *
 * @param namespace namespace of the pod the forward binds to
 * @param podName pod the forward binds to
 * @param podPort port inside the pod, i.e. what a container actually listens on
 */
public record PortForwardTarget(String namespace, String podName, int podPort) {
}
