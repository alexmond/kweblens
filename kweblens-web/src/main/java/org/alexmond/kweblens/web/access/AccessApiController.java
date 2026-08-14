package org.alexmond.kweblens.web.access;

import lombok.RequiredArgsConstructor;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import org.alexmond.kweblens.resource.ResourceDescriptor;
import org.alexmond.kweblens.web.api.UnknownResourceException;
import org.alexmond.kweblens.web.nav.ClusterNavService;

/**
 * What the service account this deployment runs as may do with a kind — the one request a
 * surface makes before it renders its actions.
 *
 * <p>
 * A {@code GET}, and therefore public in open-mode like every other read. That is the
 * right shape: the answer is about kweblens's own service account, not about a caller,
 * and <b>it authorizes nothing</b>. A control the client greys out on the strength of
 * this response is still refused server-side if it is asked for anyway —
 * {@code SecurityConfig} and the cluster's RBAC are unchanged and remain the only gates.
 */
@RestController
@RequestMapping("/api/v1/clusters/{clusterId}/access")
@RequiredArgsConstructor
public class AccessApiController {

	private final ClusterNavService clusterNav;

	private final AccessPageService access;

	/**
	 * @param resource the nav resource id of the kind on screen
	 * @param namespace the namespace on screen, omitted when the list spans all of them
	 */
	@GetMapping
	public KindAccess forKind(@PathVariable String clusterId, @RequestParam String resource,
			@RequestParam(required = false) String namespace) {
		ResourceDescriptor descriptor = this.clusterNav.find(clusterId, resource)
			.orElseThrow(() -> new UnknownResourceException(resource));
		return this.access.forKind(clusterId, descriptor, namespace);
	}

}
