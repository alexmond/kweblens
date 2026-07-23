package org.alexmond.kweblens.web.ui;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import org.alexmond.kweblens.cluster.ClusterRegistry;
import org.alexmond.kweblens.resource.ResourceService;

/**
 * Server-rendered Kubernetes dashboard. Each page is a Thymeleaf view over the same
 * access layer the JSON API uses; htmx swaps the resource table fragment as the operator
 * navigates.
 */
@Controller
@RequiredArgsConstructor
public class DashboardController {

	private final ClusterRegistry clusters;

	private final ResourceService resources;

	@GetMapping("/")
	public String home(Model model) {
		model.addAttribute("clusters", clusters.list());
		return "clusters";
	}

	@GetMapping("/clusters/{clusterId}/namespaces")
	public String namespaces(@PathVariable String clusterId, Model model) {
		model.addAttribute("clusterId", clusterId);
		model.addAttribute("kind", "Namespaces");
		model.addAttribute("rows", resources.listNamespaces(clusterId));
		return "resources";
	}

	@GetMapping("/clusters/{clusterId}/pods")
	public String pods(@PathVariable String clusterId, @RequestParam(required = false) String namespace, Model model) {
		model.addAttribute("clusterId", clusterId);
		model.addAttribute("kind", "Pods");
		model.addAttribute("rows", resources.listPods(clusterId, namespace));
		return "resources";
	}

}
