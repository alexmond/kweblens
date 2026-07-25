package org.alexmond.kweblens.portforward;

/**
 * A single active (or failed) port-forward managed by the {@link PortForwardService}. The
 * forward binds {@code localPort} on the kweblens host and tunnels it to
 * {@code remotePort} on the target Pod or Service.
 *
 * @param id stable identifier used to stop the forward
 * @param clusterId cluster the target lives in
 * @param namespace target namespace
 * @param kind {@code Pod} or {@code Service}
 * @param name target name
 * @param remotePort port on the target
 * @param localPort port bound on the kweblens host (0 until established)
 * @param address host address the local port is bound to
 * @param protocol always {@code TCP}
 * @param status {@code Active}, {@code Failed}, or {@code Closed}
 */
public record PortForwardInfo(String id, String clusterId, String namespace, String kind, String name, int remotePort,
		int localPort, String address, String protocol, String status) {

	public PortForwardInfo withStatus(String newStatus) {
		return new PortForwardInfo(id, clusterId, namespace, kind, name, remotePort, localPort, address, protocol,
				newStatus);
	}

}
