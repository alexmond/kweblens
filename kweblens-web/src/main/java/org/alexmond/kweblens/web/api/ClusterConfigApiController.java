package org.alexmond.kweblens.web.api;

import java.util.List;

import lombok.RequiredArgsConstructor;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import org.alexmond.kweblens.cluster.ClusterConfigService;
import org.alexmond.kweblens.cluster.ClusterConfigView;
import org.alexmond.kweblens.cluster.ClusterDefinition;
import org.alexmond.kweblens.cluster.ClusterInfo;
import org.alexmond.kweblens.web.security.AuditService;

/**
 * Add, edit and remove clusters at runtime, rather than only through
 * {@code kweblens.clusters[*]} and the ambient kubeconfig.
 *
 * <p>
 * Every mutating route here is a non-{@code GET}, so it is behind the admin login in both
 * security modes, and every one is audited — adding a cluster hands kweblens a credential
 * and removing one revokes access for every user of this instance, which is exactly the
 * class of action the audit trail exists for.
 *
 * <p>
 * <b>The kubeconfig only travels one way.</b> It is accepted on {@code POST}/{@code PUT}
 * and is never returned by any route on this controller — {@link ClusterConfigView}
 * reports only whether a credential is held. See that record for why.
 */
@RestController
@RequestMapping("/api/v1/clusters")
@RequiredArgsConstructor
public class ClusterConfigApiController {

	private final ClusterConfigService clusterConfig;

	private final AuditService audit;

	/** Register and persist a new cluster. */
	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public ClusterInfo add(@RequestBody ClusterDefinition definition) {
		ClusterInfo info = this.clusterConfig.add(definition);
		this.audit.record(info.id(), "cluster-add", "Cluster/" + info.id());
		return info;
	}

	/**
	 * Edit a cluster. Omit {@code kubeconfig} to keep the stored credential — renaming or
	 * switching context does not require re-supplying it.
	 */
	@PutMapping("/{clusterId}")
	public ClusterInfo update(@PathVariable String clusterId, @RequestBody ClusterDefinition definition) {
		ClusterInfo info = this.clusterConfig.update(clusterId, definition);
		this.audit.record(clusterId,
				(definition != null && definition.hasKubeconfig()) ? "cluster-update-credential" : "cluster-update",
				"Cluster/" + clusterId);
		return info;
	}

	/** Remove a cluster: its client is closed and its stored credential deleted. */
	@DeleteMapping("/{clusterId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void remove(@PathVariable String clusterId) {
		this.clusterConfig.remove(clusterId);
		this.audit.record(clusterId, "cluster-remove", "Cluster/" + clusterId);
	}

	/** How a cluster is configured — never its credential. */
	@GetMapping("/{clusterId}/config")
	public ClusterConfigView config(@PathVariable String clusterId) {
		return this.clusterConfig.describe(clusterId);
	}

	/**
	 * List the contexts in a kubeconfig the caller is about to submit, so the editor can
	 * offer them for selection. Nothing is stored; only the {@code kubeconfig} field of
	 * the body is read.
	 */
	@PostMapping("/contexts")
	public List<String> contexts(@RequestBody ClusterDefinition definition) {
		return this.clusterConfig.inspect((definition != null) ? definition.kubeconfig() : null);
	}

}
