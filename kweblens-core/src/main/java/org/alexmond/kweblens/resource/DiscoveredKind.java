package org.alexmond.kweblens.resource;

import java.util.List;

/**
 * One kind the API server says it serves, together with <b>every name it answers to</b>.
 *
 * <p>
 * A {@link ResourceDescriptor} is what the access layer needs to list something. This
 * adds the two things discovery knows and a descriptor does not: the singular name and
 * the server-declared short names ({@code po}, {@code deploy}, {@code svc}). They are
 * what makes a kind <em>addressable</em> by whatever the operator types, which is the
 * whole difference between a curated menu and a command line.
 *
 * <p>
 * <b>Short names are the server's, not ours.</b> k9s's alias file has eight entries and
 * its {@code :aliases} listed 125 rows on a plain k3s cluster; the other ~117 came from
 * here. A hand-maintained table would be a second copy of something the cluster already
 * publishes, and it would go stale silently on the first CRD.
 *
 * @param descriptor the coordinates the access layer lists by
 * @param singular the singular name, e.g. {@code pod}; {@code ""} when the server
 * declares none
 * @param shortNames the server-declared short names, in the order the server gave them;
 * empty, never null
 */
public record DiscoveredKind(ResourceDescriptor descriptor, String singular, List<String> shortNames) {

	public DiscoveredKind {
		singular = (singular != null) ? singular : "";
		shortNames = (shortNames != null) ? List.copyOf(shortNames) : List.of();
	}

	/** The plural name, which is also the path segment the API is listed at. */
	public String plural() {
		return this.descriptor.plural();
	}

	/** The Kubernetes kind, e.g. {@code Pod}. */
	public String kind() {
		return this.descriptor.kind();
	}

	/** The API group, {@code ""} for the core group. */
	public String group() {
		return this.descriptor.group();
	}

	/**
	 * {@code group/version} as the API server spells it — {@code v1} for the core group.
	 * This is the unambiguous form, and it is what a completion offers when two groups
	 * define the same plural.
	 */
	public String groupVersion() {
		String group = this.descriptor.group();
		return (group == null || group.isEmpty()) ? this.descriptor.version() : group + "/" + this.descriptor.version();
	}

}
