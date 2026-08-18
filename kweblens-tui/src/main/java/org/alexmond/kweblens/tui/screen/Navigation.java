package org.alexmond.kweblens.tui.screen;

import java.util.function.Predicate;

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;

import org.alexmond.kweblens.tui.data.ObjectDetail;
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

	/**
	 * Show this kind, at this scope, narrowed by this predicate.
	 *
	 * <p>
	 * <b>It returns what could not be done rather than throwing it</b> (GH#434). A
	 * navigation subscribes and lists on the render thread, and either call can be
	 * refused — a cluster that went away between the keystroke and the list, an RBAC
	 * refusal on a kind the discovery document still names. An exception from here leaves
	 * the event loop, and TamboUI's runner answers that by replacing the whole screen
	 * with a stack trace it never comes back from; a sentence is answered by the footer
	 * the controller already keeps for exactly this.
	 * @return what could not be done, or empty when the view was filled
	 */
	String show(ResourceQuery query, Predicate<ResourceRow> filter);

	/**
	 * Everything the detail pane draws about one object, as the server computed it
	 * (GH#368).
	 *
	 * <p>
	 * <b>It reports a refusal rather than throwing one</b>, for the same reason
	 * {@link #show} does: this runs on the render thread inside a key press, and an
	 * exception out of an {@code EventHandler} is caught by TamboUI's runner, which
	 * replaces the screen with a stack trace it never comes back from.
	 * @param namespace the object's namespace, empty or null for a cluster-scoped kind
	 * @param name the object's name
	 * @return the detail, or one carrying the reason there is nothing to show
	 */
	ObjectDetail detail(String namespace, String name);

}
