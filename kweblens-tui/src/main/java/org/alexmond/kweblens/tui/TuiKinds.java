package org.alexmond.kweblens.tui;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.alexmond.kweblens.resource.ResourceDescriptor;
import org.alexmond.kweblens.resource.WellKnownKinds;

/**
 * The kinds v1 can name on the command line, by their kweblens ids.
 *
 * <p>
 * <b>This is a placeholder and is meant to be deleted.</b> The real answer is discovery:
 * k9s's {@code :aliases} was 125 rows on a plain k3s cluster grown from eight
 * hand-written entries, and kweblens already discovers CRDs server-side. #365 replaces
 * this map with aliases over discovered GVRs. Until then, listing the handful of kinds
 * {@link WellKnownKinds} already defines is enough for "starts, lists one kind, exits"
 * and adds no second catalog to keep in sync — every descriptor here is core's own, not a
 * copy of one.
 */
public final class TuiKinds {

	private static final Map<String, ResourceDescriptor> BY_ID = byId(WellKnownKinds.PODS, WellKnownKinds.NODES,
			WellKnownKinds.NAMESPACES, WellKnownKinds.EVENTS, WellKnownKinds.SERVICES,
			WellKnownKinds.PERSISTENT_VOLUME_CLAIMS, WellKnownKinds.CONFIG_MAPS, WellKnownKinds.SECRETS);

	private TuiKinds() {
	}

	private static Map<String, ResourceDescriptor> byId(ResourceDescriptor... descriptors) {
		Map<String, ResourceDescriptor> map = new LinkedHashMap<>();
		for (ResourceDescriptor descriptor : descriptors) {
			map.put(descriptor.id(), descriptor);
		}
		return Map.copyOf(map);
	}

	/** The descriptor for an id, or empty if this build does not know that id. */
	public static Optional<ResourceDescriptor> byId(String id) {
		return Optional.ofNullable((id != null) ? BY_ID.get(id.trim().toLowerCase(Locale.ROOT)) : null);
	}

	/** Every id this build knows, sorted, for an error message that helps. */
	public static List<String> ids() {
		return BY_ID.keySet().stream().sorted().toList();
	}

}
