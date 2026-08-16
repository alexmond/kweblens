package org.alexmond.kweblens.resource;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import io.fabric8.kubernetes.api.model.APIGroup;
import io.fabric8.kubernetes.api.model.APIGroupList;
import io.fabric8.kubernetes.api.model.APIResource;
import io.fabric8.kubernetes.api.model.APIResourceList;
import io.fabric8.kubernetes.api.model.GroupVersionForDiscovery;
import io.fabric8.kubernetes.client.KubernetesClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;

import org.alexmond.kweblens.cluster.ClusterRegistry;

/**
 * Every kind the cluster actually serves, asked of the cluster rather than listed here.
 *
 * <h2>Why this exists next to a curated catalog</h2>
 *
 * {@code NavCatalog} orders a menu: seven categories and the kinds worth putting in front
 * of someone, which is why the left nav reads as a product. It cannot address a kind
 * nobody wrote down. Discovery is the other half — the API server publishes, for every
 * group/version it serves, each resource's plural, singular, kind and short names, and
 * that set includes every CRD the moment it is installed. <b>Both are wanted.</b> The
 * catalog decides what is offered; this decides what is reachable.
 *
 * <h2>What is deliberately dropped</h2>
 *
 * <ul>
 * <li><b>Subresources</b> — {@code pods/log}, {@code deployments/scale}. They appear in
 * discovery with a {@code /} in the name and cannot be listed at all.</li>
 * <li><b>Anything without the {@code list} verb.</b> A kind that cannot be listed is not
 * a kind a table can show, and offering it would be a completion that leads to a
 * 405.</li>
 * </ul>
 *
 * <h2>Failure is partial, not total</h2>
 *
 * An aggregated API group whose service is down makes {@code /apis/<group>/<version>}
 * fail while every other group answers perfectly well. Each group is therefore collected
 * inside its own try/catch: a broken group costs its own kinds and nothing else. A
 * cluster that cannot be reached at all yields an empty list and a warning, exactly as
 * {@link CrdService} does — the caller's job is to say "this build could not discover
 * anything", not to crash.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ApiDiscoveryService {

	/** The verb a kind must publish to be worth putting in a table. */
	private static final String LIST = "list";

	/**
	 * The core group's only version. {@code /api} has listed exactly {@code v1} since
	 * Kubernetes 1.0 and the path is part of the API contract, so this is a constant of
	 * the protocol rather than something to discover.
	 */
	private static final String CORE_VERSION = "v1";

	private final ClusterRegistry clusters;

	/**
	 * Every listable kind the cluster serves, sorted by group then plural so the order is
	 * the same on two calls and a completion list does not reshuffle itself.
	 *
	 * <p>
	 * <b>Not cached here.</b> Discovery is one round trip per group/version and a caller
	 * that asks for it on every keystroke would feel it — but the right lifetime for the
	 * answer belongs to whoever is holding a screen open, not to a service that cannot
	 * see the screen. The TUI memoises it per cluster; a caching layer here would be a
	 * second TTL nobody could see.
	 */
	public List<DiscoveredKind> kinds(String clusterId) {
		KubernetesClient client;
		try {
			client = this.clusters.require(clusterId);
		}
		catch (RuntimeException ex) {
			log.warn("Could not open cluster '{}' for discovery: {}", clusterId, ex.getMessage());
			return List.of();
		}
		List<DiscoveredKind> found = new ArrayList<>();
		collect(client, "", CORE_VERSION, found);
		collect(client, groups(client), found);
		found.sort(Comparator.comparing(DiscoveredKind::group).thenComparing(DiscoveredKind::plural));
		return List.copyOf(found);
	}

	private List<APIGroup> groups(KubernetesClient client) {
		try {
			APIGroupList list = client.getApiGroups();
			return (list != null && list.getGroups() != null) ? list.getGroups() : List.of();
		}
		catch (RuntimeException ex) {
			log.warn("Could not list API groups: {}", ex.getMessage());
			return List.of();
		}
	}

	private void collect(KubernetesClient client, List<APIGroup> groups, List<DiscoveredKind> into) {
		for (APIGroup group : groups) {
			String version = preferredVersion(group);
			if (group.getName() != null && version != null) {
				collect(client, group.getName(), version, into);
			}
		}
	}

	/**
	 * One group/version's resources. The group and version are passed in rather than read
	 * off each {@link APIResource}, because the API server leaves both fields empty on
	 * every resource in its own group's list — they are implied by the path that was
	 * asked for.
	 */
	private void collect(KubernetesClient client, String group, String version, List<DiscoveredKind> into) {
		String groupVersion = (group.isEmpty()) ? version : group + "/" + version;
		List<APIResource> resources;
		try {
			APIResourceList list = client.getApiResources(groupVersion);
			resources = (list != null && list.getResources() != null) ? list.getResources() : List.of();
		}
		catch (RuntimeException ex) {
			// One aggregated API being down must not cost the other forty groups.
			log.warn("Could not read API resources for '{}': {}", groupVersion, ex.getMessage());
			return;
		}
		for (APIResource resource : resources) {
			DiscoveredKind kind = toKind(resource, group, version);
			if (kind != null) {
				into.add(kind);
			}
		}
	}

	private DiscoveredKind toKind(APIResource resource, String group, String version) {
		String plural = resource.getName();
		String kind = resource.getKind();
		if (plural == null || kind == null || plural.indexOf('/') >= 0 || !listable(resource)) {
			return null;
		}
		boolean namespaced = Boolean.TRUE.equals(resource.getNamespaced());
		String id = (group.isEmpty()) ? plural : group + "." + plural;
		ResourceDescriptor descriptor = new ResourceDescriptor(id, kind, kind, group, version, plural, namespaced,
				false);
		return new DiscoveredKind(descriptor, resource.getSingularName(), resource.getShortNames());
	}

	private boolean listable(APIResource resource) {
		List<String> verbs = resource.getVerbs();
		return verbs != null && verbs.contains(LIST);
	}

	/**
	 * The version the server itself prefers for a group, falling back to the first it
	 * lists. Picking the highest-sorting name instead would promote {@code v1beta1} over
	 * {@code v1} on some groups and {@code v2} over {@code v1} on others.
	 */
	private static String preferredVersion(APIGroup group) {
		GroupVersionForDiscovery preferred = group.getPreferredVersion();
		if (preferred != null && preferred.getVersion() != null) {
			return preferred.getVersion();
		}
		List<GroupVersionForDiscovery> versions = group.getVersions();
		return (versions == null || versions.isEmpty()) ? null : versions.get(0).getVersion();
	}

}
