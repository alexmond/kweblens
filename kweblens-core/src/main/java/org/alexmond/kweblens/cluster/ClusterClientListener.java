package org.alexmond.kweblens.cluster;

/**
 * Notified when the {@link ClusterRegistry} closes a cluster's client — because the
 * cluster was removed, or because the same id was re-registered against a different one.
 *
 * <p>
 * The registry owns client lifecycles, but a client is not the only thing a cluster
 * accumulates. Some resources are held <em>outside</em> the client and are not released
 * when it closes: a port-forward's listening socket is bound by kweblens on the kweblens
 * host, so closing the client kills the tunnel and leaves the port bound; a cached
 * OpenAPI document describes an API server that this id may no longer point at. This is
 * the hook that lets those holders find out, so that "a removed cluster must stop holding
 * sockets and threads, not merely disappear from the list" stays true of everything the
 * cluster owned rather than only of the client.
 *
 * <p>
 * Holders register themselves ({@link ClusterRegistry#addClientListener}) rather than the
 * registry knowing about them, because the dependency only runs one way: everything
 * depends on the registry, and the registry depends on nothing.
 */
@FunctionalInterface
public interface ClusterClientListener {

	/**
	 * The client for {@code clusterId} has just been closed. The registry either no
	 * longer holds that id at all, or holds a different client for it — either way
	 * anything derived from the old one is dead and should be released.
	 * @param clusterId the cluster whose client was closed
	 */
	void clusterClientClosed(String clusterId);

}
