package org.alexmond.kweblens.tui.screen;

import org.alexmond.kweblens.resource.ResourceDescriptor;

/**
 * One level of the view stack: a kind, a scope, and the filter that is in force there.
 *
 * <h2>The filter belongs to the level, and that is the interesting part</h2>
 *
 * k9s implements drill-down <b>as a filter and says so</b>: entering a Deployment lands
 * on {@code pods(kube-system/coredns)[1] </k8s-app=kube-dns>}. The relationship between
 * the two objects is not a hidden join the app performed on your behalf — it is a query,
 * on screen, in the same language you would have typed, and you can edit it. That is
 * worth stealing outright, and it is why the filter is a field of the view rather than a
 * global: popping back to the Deployment restores the Deployment's filter, not the pods'
 * one.
 *
 * <p>
 * <b>The query is this product's grammar, not k9s's.</b> {@code k8s-app=kube-dns} is
 * already a legal label requirement in {@code ObjectFilter}, which is a port of the
 * browser's {@code objectFilter.ts}. Two surfaces with two filter languages would be a
 * support burden and a documentation lie (#366), so a drill-down that could only be
 * expressed in a second grammar is a drill-down this app does not offer.
 *
 * @param descriptor the kind this level shows
 * @param namespace the namespace, or null/blank for every namespace
 * @param filter the filter query in force, {@code ""} for none
 * @param crumb what the breadcrumb says, e.g. {@code pods}
 */
public record View(ResourceDescriptor descriptor, String namespace, String filter, String crumb) {

	public View {
		filter = (filter != null) ? filter : "";
	}

	/** A view of a kind with no filter, its breadcrumb taken from the kind's plural. */
	public static View of(ResourceDescriptor descriptor, String namespace) {
		return new View(descriptor, namespace, "", descriptor.plural());
	}

	/** A view of a kind narrowed by a query. */
	public static View of(ResourceDescriptor descriptor, String namespace, String filter) {
		return new View(descriptor, namespace, filter, descriptor.plural());
	}

	/** The same view with a different filter. */
	public View withFilter(String query) {
		return new View(this.descriptor, this.namespace, query, this.crumb);
	}

	/** Whether a filter is in force here. */
	public boolean filtered() {
		return !this.filter.isBlank();
	}

	/**
	 * The scope as the frame title writes it: the namespace, {@code all}, or nothing at
	 * all for a cluster-scoped kind — a Node's namespace is not missing, it does not
	 * exist.
	 */
	public String scope() {
		if (!this.descriptor.namespaced()) {
			return "";
		}
		return (this.namespace == null || this.namespace.isBlank()) ? "all" : this.namespace;
	}

}
