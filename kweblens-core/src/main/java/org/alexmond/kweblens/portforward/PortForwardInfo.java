package org.alexmond.kweblens.portforward;

/**
 * A single active (or failed) port-forward managed by the {@link PortForwardService}. The
 * forward binds {@code localPort} on the kweblens host and tunnels it to
 * {@code remotePort} on the target Pod or Service.
 *
 * <p>
 * For a Service, {@code remotePort} is the port that was <em>asked for</em> (the
 * Service's own port) while {@code podPort} is where the traffic actually lands — the
 * resolved {@code targetPort} on {@code podName}. The two differ whenever a Service
 * remaps a port (podinfo's {@code 80 -> 9898}), so both are reported rather than only the
 * request.
 *
 * @param id stable identifier used to stop the forward
 * @param clusterId cluster the target lives in
 * @param namespace target namespace
 * @param kind {@code Pod} or {@code Service}
 * @param name target name
 * @param remotePort port requested on the target
 * @param podPort port the forward reaches inside the pod
 * @param podName pod the forward binds to
 * @param localPort port bound on the kweblens host (0 until established)
 * @param address host address the local port is bound to
 * @param protocol always {@code TCP}
 * @param status {@code Active}, {@code Failed}, or {@code Closed}
 */
public record PortForwardInfo(String id, String clusterId, String namespace, String kind, String name, int remotePort,
		int podPort, String podName, int localPort, String address, String protocol, String status) {

	public PortForwardInfo withStatus(String newStatus) {
		return new PortForwardInfo(id, clusterId, namespace, kind, name, remotePort, podPort, podName, localPort,
				address, protocol, newStatus);
	}

}
