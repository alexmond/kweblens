package org.alexmond.kweblens.tui.screen;

import java.util.function.Predicate;

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;

import org.alexmond.kweblens.tui.data.ResourceQuery;
import org.alexmond.kweblens.tui.kind.KindIndex;

/**
 * What {@link ViewController} may ask the session to do when a key means "go somewhere".
 *
 * <p>
 * The controller decides <b>where</b> — it owns the view stack, the history and the
 * prompt — and this does the <b>how</b>: closing a watch, re-pointing a projection,
 * re-listing. Splitting it here is what keeps every navigation rule in this package, with
 * no TamboUI type anywhere near it, so {@code ViewControllerTest} drives the real
 * controller against a handful of fixtures instead of against a terminal.
 */
public interface Navigation {

	/** The cluster the screen is open on. */
	String clusterId();

	/**
	 * Every kind this cluster serves, by every name it answers to. Discovered once per
	 * cluster and remembered — see {@code KindCatalog} for why the lifetime is what it
	 * is.
	 */
	KindIndex kinds();

	/**
	 * The whole of one object, for a drill-down. A list row is not enough: the selector
	 * that says which pods belong to a Deployment is in its spec, and the model
	 * deliberately holds rows rather than objects.
	 */
	GenericKubernetesResource object(String namespace, String name);

	/** Show this kind, at this scope, narrowed by this predicate. */
	void show(ResourceQuery query, Predicate<ResourceRow> filter);

}
