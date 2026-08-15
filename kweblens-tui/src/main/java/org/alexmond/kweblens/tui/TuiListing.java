package org.alexmond.kweblens.tui;

import java.io.PrintWriter;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;

import org.alexmond.kweblens.health.ObjectState;
import org.alexmond.kweblens.tui.data.ClusterDataSource;
import org.alexmond.kweblens.tui.data.ResourceQuery;

/**
 * Lists one kind to a writer — the whole of what v1 draws.
 *
 * <p>
 * <b>This is not the table.</b> The scrollable, selectable, live-updating table is #364,
 * and the per-kind columns are #367; this exists so the module has a runnable path that
 * proves the port reaches a real cluster and exits, and so the port is exercised by
 * something before seven tickets are built on it. It writes to a {@link PrintWriter}
 * rather than to {@code System.out} so the output is a value a test can read.
 *
 * <p>
 * Three deliberate choices survive into #364:
 * <ul>
 * <li><b>Pages, not a list.</b> Rows are written as each page arrives and the page is
 * dropped; nothing here ever holds the whole kind. A terminal has no more heap than a
 * browser does.</li>
 * <li><b>The verdict comes from {@code ObjectStates}</b>, through the port, never from
 * re-reading a phase field here. There is exactly one implementation of what "Running"
 * means in this product and it is not in the presentation layer.</li>
 * <li><b>One context per page, not per row.</b> {@code ObjectStates.forList} opens the
 * status context once per call, so calling it per page costs one open per page. Holding
 * every page to get a single open would spend exactly the heap the paging exists to
 * bound.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class TuiListing {

	/**
	 * Nothing here judges this object — an Event, a ServiceAccount, a CRD, or a kind
	 * whose context could not be fetched. It is not "OK", and it must not read as a blank
	 * cell that could be mistaken for one.
	 */
	private static final String NO_VERDICT = "—";

	private static final String ROW_FORMAT = "%-28s %-52s %s%n";

	private final ClusterDataSource cluster;

	/**
	 * Write the header and every row of {@code query}'s kind.
	 * @return how many rows were written
	 */
	public int print(ResourceQuery query, int chunkSize, PrintWriter out) {
		out.println(header(query));
		out.printf(ROW_FORMAT, "NAMESPACE", "NAME", "STATE");
		AtomicInteger rows = new AtomicInteger();
		this.cluster.list(query, chunkSize, (page) -> rows.addAndGet(printPage(query, page, out)));
		out.flush();
		return rows.get();
	}

	/**
	 * The posture, the cluster and the scope, on one line — so an operator can see what
	 * they are looking at and whether it can be changed, rather than assuming either.
	 */
	String header(ResourceQuery query) {
		String scope = (query.namespace() == null || query.namespace().isBlank()) ? "all namespaces"
				: query.namespace();
		return String.join(" · ", "kweblens-tui " + TuiPosture.current().badge(), query.clusterId(), query.kind(),
				scope);
	}

	private int printPage(ResourceQuery query, List<GenericKubernetesResource> page, PrintWriter out) {
		List<Optional<ObjectState>> states = this.cluster.states(query, page);
		for (int i = 0; i < page.size(); i++) {
			ObjectMeta metadata = page.get(i).getMetadata();
			String namespace = (metadata != null && metadata.getNamespace() != null) ? metadata.getNamespace() : "";
			String name = (metadata != null && metadata.getName() != null) ? metadata.getName() : "";
			out.printf(ROW_FORMAT, namespace, name, label(states, i));
		}
		return page.size();
	}

	/**
	 * The verdict for row {@code i}. {@code states} is positional and the same length as
	 * the page, but a source that returned a shorter list would silently shift every
	 * verdict onto the wrong row, so the length is checked rather than trusted.
	 */
	private static String label(List<Optional<ObjectState>> states, int i) {
		if (i >= states.size()) {
			return NO_VERDICT;
		}
		return states.get(i).map(ObjectState::label).orElse(NO_VERDICT);
	}

}
