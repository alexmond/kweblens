package org.alexmond.kweblens.web.nav;

import java.util.List;

import org.alexmond.kweblens.resource.ResourceDescriptor;

/**
 * One collapsible group in the left navigation — a label, a Bootstrap icon class, and the
 * kinds it contains. Rendered by the shell; the kinds double as the route table (each is
 * reachable at {@code /clusters/{cluster}/r/{id}}).
 *
 * @param label group label (e.g. {@code Workloads})
 * @param icon Bootstrap-icons class for the group (e.g. {@code bi-box-seam})
 * @param items the kinds in this group, in display order
 */
public record NavCategory(String label, String icon, List<ResourceDescriptor> items) {
}
