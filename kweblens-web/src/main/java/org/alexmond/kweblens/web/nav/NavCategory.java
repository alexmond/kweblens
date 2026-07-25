package org.alexmond.kweblens.web.nav;

import java.util.List;

import org.alexmond.kweblens.resource.ResourceDescriptor;

/**
 * One collapsible group in the left navigation — a label, a Bootstrap icon class, the
 * kinds it contains, and optional nested sub-groups (used by <b>Custom Resources</b> to
 * nest one collapsible group per CRD API group underneath it). Rendered by the shell; the
 * kinds double as the route table (each is reachable at
 * {@code /clusters/{cluster}/r/{id}}).
 *
 * @param label group label (e.g. {@code Workloads})
 * @param icon Bootstrap-icons class for the group (e.g. {@code bi-box-seam})
 * @param items the kinds in this group, in display order
 * @param subgroups nested categories rendered under this one (empty for flat groups)
 */
public record NavCategory(String label, String icon, List<ResourceDescriptor> items, List<NavCategory> subgroups) {

	/** A flat group with no nested sub-groups. */
	public NavCategory(String label, String icon, List<ResourceDescriptor> items) {
		this(label, icon, items, List.of());
	}
}
