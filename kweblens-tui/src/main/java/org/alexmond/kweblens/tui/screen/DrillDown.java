package org.alexmond.kweblens.tui.screen;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.ObjectMeta;

/**
 * What pressing enter on a row means — <b>as a filter you can read and edit</b>.
 *
 * <h2>The relationship is not a hidden join</h2>
 *
 * This is the one thing worth taking from k9s outright. Entering a Deployment does not
 * perform a private lookup and hand you "its" pods; it opens the pods list with
 * {@code k8s-app=kube-dns} in the title, in the same query language the filter box takes.
 * Two things follow, and both matter more than they look:
 * <ul>
 * <li><b>You can check it.</b> A wrong relationship is visible as a wrong query rather
 * than as a list you have no way to disagree with.</li>
 * <li><b>You can edit it.</b> Widen the selector, add a {@code status:} term, drop it
 * entirely — the drill-down is a starting point, not a mode.</li>
 * </ul>
 *
 * <p>
 * <b>The query is kweblens's grammar</b> (#366's port of {@code objectFilter.ts}), never
 * k9s's. {@code k8s-app=kube-dns} is already a legal label requirement in it, which is
 * why this steal is cheap.
 *
 * <h2>Where it declines, it says why</h2>
 *
 * A Node's pods are selected by {@code spec.nodeName}, a field selector, and this grammar
 * has exactly one field selector ({@code status:}) on purpose. A selector written with
 * {@code matchExpressions} has no spelling here either. Both come back as an
 * {@link #decline(String) explanation} rather than as an empty list or a query that
 * selects the wrong rows — the second is the failure mode the whole family of these links
 * exists to avoid.
 */
public final class DrillDown {

	private DrillDown() {
	}

	/**
	 * Where enter goes from {@code object}, or a sentence saying why it goes nowhere.
	 * @param kind the object's kind, as the API server spells it
	 * @param object the whole object — a list row is not enough, because the selector is
	 * in the spec
	 */
	public static Target from(String kind, GenericKubernetesResource object) {
		if (object == null) {
			return decline("Nothing selected.");
		}
		String namespace = namespaceOf(object);
		String name = nameOf(object);
		if ("Namespace".equals(kind)) {
			return new Target("pods", name, "", null);
		}
		if ("Node".equals(kind)) {
			return decline("A Node's pods are selected by spec.nodeName, and this filter grammar has no "
					+ "field selectors. Use :pods and filter by name.");
		}
		if ("Pod".equals(kind)) {
			// Event names begin with the involved object's name, so a name: term reaches
			// them. It is a heuristic and it is on screen as one: the term is editable
			// and
			// the reader can see exactly what it selected on.
			return new Target("events", namespace, "name:" + name, null);
		}
		if ("Service".equals(kind)) {
			return fromSelector(namespace, map(spec(object), "selector"), "Service");
		}
		Map<String, Object> selector = map(spec(object), "selector");
		if (selector != null) {
			return fromSelector(namespace, map(selector, "matchLabels"), kind);
		}
		return decline("Nothing to drill into from a " + kind + ". Use : to open another kind.");
	}

	private static Target fromSelector(String namespace, Map<String, Object> labels, String kind) {
		if (labels == null || labels.isEmpty()) {
			return decline("This " + kind + " selects its pods with something other than matchLabels "
					+ "(matchExpressions), which this filter grammar cannot write.");
		}
		List<String> terms = new ArrayList<>(labels.size());
		for (Map.Entry<String, Object> entry : labels.entrySet()) {
			if (entry.getValue() != null) {
				terms.add(entry.getKey() + "=" + entry.getValue());
			}
		}
		if (terms.isEmpty()) {
			return decline("This " + kind + "'s selector has no values to match on.");
		}
		return new Target("pods", namespace, String.join(" ", terms), null);
	}

	private static Target decline(String reason) {
		return new Target("", null, "", reason);
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> spec(GenericKubernetesResource object) {
		Object spec = object.getAdditionalProperties().get("spec");
		return (spec instanceof Map) ? (Map<String, Object>) spec : null;
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> map(Map<String, Object> parent, String key) {
		Object value = (parent != null) ? parent.get(key) : null;
		return (value instanceof Map) ? (Map<String, Object>) value : null;
	}

	private static String namespaceOf(GenericKubernetesResource object) {
		ObjectMeta metadata = object.getMetadata();
		return (metadata != null) ? metadata.getNamespace() : null;
	}

	private static String nameOf(GenericKubernetesResource object) {
		ObjectMeta metadata = object.getMetadata();
		return (metadata != null && metadata.getName() != null) ? metadata.getName() : "";
	}

	/**
	 * Where enter leads: a kind to open, a namespace to scope it to, and the filter that
	 * says what the relationship is — or a reason there is nowhere to go.
	 *
	 * @param kind the kind alias to resolve against the cluster's discovered kinds
	 * @param namespace the namespace to scope to, null for all
	 * @param filter the query that expresses the relationship, {@code ""} for none
	 * @param reason why there is nowhere to go, or null. Never set with a kind.
	 */
	public record Target(String kind, String namespace, String filter, String reason) {

		/** Whether there is somewhere to go. */
		public boolean available() {
			return this.reason == null;
		}

	}

}
