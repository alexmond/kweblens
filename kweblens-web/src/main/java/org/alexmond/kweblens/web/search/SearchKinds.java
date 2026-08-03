package org.alexmond.kweblens.web.search;

import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The bounded set of kinds global search looks in, as nav route ids.
 *
 * <p>
 * <b>Why bounded.</b> A real cluster here carries 118 kinds — 332 workload objects, 281
 * config objects, 349 access-control objects and 263 custom resources. Listing all of
 * them per keystroke is not affordable, and most of them are things nobody searches for
 * by name (a Lease, an EndpointSlice, a RoleBinding). These thirteen are the kinds people
 * actually have a name for. Everything else is <b>named in the response</b> as unsearched
 * rather than quietly omitted — a search that looks complete and is not is worse than a
 * narrow one that says so.
 *
 * <p>
 * The ids are resolved against the <b>per-cluster</b> nav rather than re-spelling
 * descriptors here, so this list cannot drift from the catalog: a kind renamed in
 * {@code NavCatalog} simply stops resolving and drops out of the searched set (and into
 * the unsearched list) instead of failing at runtime.
 */
final class SearchKinds {

	/**
	 * Kinds whose names people choose — you deploy "sonarqube", you do not name the pod.
	 * A hit here carries {@link SearchRanking#PRIMARY_KIND_BONUS}.
	 */
	private static final List<String> PRIMARY = List.of("deployments", "statefulsets", "daemonsets", "cronjobs",
			"services", "ingresses", "configmaps", "secrets", "persistentvolumeclaims", "namespaces", "nodes");

	/**
	 * Kinds whose names the cluster generates from a primary object's. Searched — you do
	 * sometimes have a pod name off a log line — but never ranked above the object that
	 * produced them at equal match quality. This is the fix for the crowding in the
	 * reference implementation, where 5 of 20 rows were generated objects.
	 *
	 * <p>
	 * ReplicaSets are deliberately <b>not</b> here at all: they are pure churn (one per
	 * Deployment revision), and searching them means every result set carries several
	 * rows nobody asked for. They appear in the unsearched list, so the omission is
	 * stated.
	 */
	private static final List<String> GENERATED = List.of("pods", "jobs");

	private static final Set<String> PRIMARY_SET = Set.copyOf(PRIMARY);

	private SearchKinds() {
	}

	/** Every searched route id, primary kinds first. */
	static List<String> ids() {
		return Stream.concat(PRIMARY.stream(), GENERATED.stream()).toList();
	}

	/** The kind bonus for a route id — see {@link SearchRanking#PRIMARY_KIND_BONUS}. */
	static int bonus(String resourceId) {
		return PRIMARY_SET.contains(resourceId) ? SearchRanking.PRIMARY_KIND_BONUS : 0;
	}

}
