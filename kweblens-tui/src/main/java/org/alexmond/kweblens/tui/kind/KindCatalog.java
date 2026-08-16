package org.alexmond.kweblens.tui.kind;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;

import org.alexmond.kweblens.tui.data.ClusterDataSource;

/**
 * One {@link KindIndex} per cluster, discovered once and remembered for as long as the
 * process lives.
 *
 * <p>
 * <b>The lifetime is deliberate and it is short of perfect.</b> Discovery is a round trip
 * per group/version — 30-odd requests on a plain k3s — so it cannot happen on a
 * keystroke; a CRD installed <em>while the screen is up</em> is therefore not addressable
 * until {@link #forget} or a restart. That is the honest trade for a command line that
 * answers instantly, and it is stated here rather than left for someone to discover: the
 * alternative is a TTL nobody can see whose expiry lands in the middle of typing.
 *
 * <p>
 * Keyed by cluster id because a kind index describes one API server. Pointing an id at a
 * different cluster must drop it — {@link #forget} is that, and it is the same rule
 * {@code SchemaService} keeps for its cached OpenAPI document.
 */
@Service
@RequiredArgsConstructor
public class KindCatalog {

	private final ClusterDataSource cluster;

	private final Map<String, KindIndex> byCluster = new ConcurrentHashMap<>();

	/**
	 * The index for a cluster, discovering it on first use. Never null: a cluster that
	 * refuses discovery yields {@link KindIndex#empty()}, so the caller renders "this
	 * build could not discover anything" rather than throwing inside a keystroke.
	 */
	public KindIndex of(String clusterId) {
		return this.byCluster.computeIfAbsent(clusterId, (id) -> KindIndex.of(this.cluster.kinds(id)));
	}

	/**
	 * Drop what was discovered for a cluster, so the next ask re-reads the API server.
	 */
	public void forget(String clusterId) {
		this.byCluster.remove(clusterId);
	}

}
