package org.alexmond.kweblens.tui.screen;

/**
 * The line above the table: <b>what you are looking at, how much of it there is, and what
 * is hiding the rest.</b>
 *
 * <pre>
 * deployments(kube-system)[4]
 * pods(all)[1] &lt;/coredns&gt;
 * pods(kube-system)[1] &lt;/k8s-app=kube-dns&gt;
 * nodes[3]
 * </pre>
 *
 * <p>
 * The filter is in the title rather than only in the prompt, and that is the honest half:
 * a table showing 1 of 137 rows with nothing on screen to say why is a table that has
 * lied to you. It is also what makes drill-down readable — the third line above is what
 * entering a Deployment produces, and it says in the query language exactly which pods
 * these are and why.
 *
 * <p>
 * A cluster-scoped kind gets <b>no parentheses at all</b>. Writing {@code nodes(all)}
 * would claim the list was narrowed by a namespace and was not.
 */
public final class FrameTitle {

	private FrameTitle() {
	}

	/**
	 * The title for a view.
	 * @param view the kind, the scope and the filter
	 * @param rows how many rows are on screen — <b>after</b> the filter, because that is
	 * the number a reader is counting
	 */
	public static String of(View view, int rows) {
		StringBuilder title = new StringBuilder(64);
		title.append(view.crumb());
		String scope = view.scope();
		if (!scope.isEmpty()) {
			title.append('(').append(scope).append(')');
		}
		title.append('[').append(rows).append(']');
		if (view.filtered()) {
			title.append(" </").append(view.filter()).append('>');
		}
		return title.toString();
	}

}
