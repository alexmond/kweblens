package org.alexmond.kweblens.web.api;

import java.util.List;

import lombok.RequiredArgsConstructor;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import org.alexmond.kweblens.web.nav.ClusterNavService;
import org.alexmond.kweblens.web.nav.NavCategory;

/**
 * Exposes a cluster's navigation tree (static categories + the dynamic Custom Resources
 * groups) as JSON, so the React UI can render the same collapsible Navigator the
 * server-rendered dashboard builds.
 */
@RestController
@RequestMapping("/api/v1/clusters/{clusterId}")
@RequiredArgsConstructor
public class NavApiController {

	private final ClusterNavService clusterNav;

	@GetMapping("/nav")
	public List<NavCategory> nav(@PathVariable String clusterId) {
		return clusterNav.categories(clusterId);
	}

}
