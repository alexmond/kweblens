package org.alexmond.kweblens.tui.screen;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;

import org.alexmond.kweblens.health.ObjectState;
import org.alexmond.kweblens.tui.data.ClusterDataSource;
import org.alexmond.kweblens.tui.data.ResourceQuery;

/**
 * The one {@link RowBatch} that asks a cluster: verdicts through
 * {@code ObjectStates.forList}, via the port.
 *
 * <p>
 * <b>One call per batch, one {@code StatusContext} per call.</b> That is the only rule
 * this class has to keep, and it is why the seam takes a list. The verdicts come back
 * positionally — same size, same order — so a shorter list would silently shift every
 * verdict onto the wrong row; the length is checked rather than trusted, exactly as
 * {@code TuiListing} does.
 *
 * <p>
 * The query is the one the objects were listed with. Passing a different namespace here
 * would open a context describing a different set of objects than the rows do.
 */
public class CoreRowBatch implements RowBatch {

	private final ClusterDataSource cluster;

	private final ResourceQuery query;

	private final Clock clock;

	public CoreRowBatch(ClusterDataSource cluster, ResourceQuery query) {
		this(cluster, query, Clock.systemUTC());
	}

	public CoreRowBatch(ClusterDataSource cluster, ResourceQuery query, Clock clock) {
		this.cluster = cluster;
		this.query = query;
		this.clock = clock;
	}

	@Override
	public List<ResourceRow> project(List<GenericKubernetesResource> objects) {
		if (objects.isEmpty()) {
			return List.of();
		}
		List<Optional<ObjectState>> states = this.cluster.states(this.query, objects);
		Instant now = this.clock.instant();
		List<ResourceRow> rows = new ArrayList<>(objects.size());
		for (int i = 0; i < objects.size(); i++) {
			rows.add(ResourceRow.of(objects.get(i), label(states, i), now));
		}
		return rows;
	}

	/**
	 * The verdict for object {@code i}, or null when nothing judges it — which is a third
	 * answer and is rendered as a dash, never as an empty cell that could read as "fine".
	 */
	private static String label(List<Optional<ObjectState>> states, int i) {
		if (i >= states.size()) {
			return null;
		}
		return states.get(i).map(ObjectState::label).orElse(null);
	}

}
