package org.alexmond.kweblens.web.ui;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import org.alexmond.kweblens.cluster.ClusterRegistry;
import org.alexmond.kweblens.event.EventService;
import org.alexmond.kweblens.metric.MetricService;
import org.alexmond.kweblens.resource.ResourceDescriptor;
import org.alexmond.kweblens.resource.ResourceService;
import org.alexmond.kweblens.web.helm.HelmService;
import org.alexmond.kweblens.web.nav.NavCatalog;

/**
 * Server-rendered Kubernetes dashboard. The cluster index lists connected clusters;
 * inside a cluster, a Freelens-style shell (left category nav + resource table) renders
 * any kind through the generic access path, driven entirely by the {@link NavCatalog}.
 */
@Controller
@RequiredArgsConstructor
public class DashboardController {

	private final ClusterRegistry clusters;

	private final ResourceService resources;

	private final EventService events;

	private final MetricService metrics;

	private final HelmService helm;

	private final NavCatalog navCatalog;

	@GetMapping("/")
	public String home(Model model) {
		model.addAttribute("clusters", clusters.list());
		return "clusters";
	}

	@GetMapping("/clusters/{clusterId}")
	public String cluster(@PathVariable String clusterId) {
		return "redirect:/clusters/" + clusterId + "/r/pods";
	}

	@GetMapping("/clusters/{clusterId}/r/{resourceId}")
	public String resource(@PathVariable String clusterId, @PathVariable String resourceId,
			@RequestParam(required = false) String namespace, Model model) {
		ResourceDescriptor descriptor = navCatalog.find(resourceId)
			.orElseThrow(() -> new UnknownResourceException(resourceId));
		shell(model, clusterId, resourceId);
		model.addAttribute("descriptor", descriptor);
		model.addAttribute("namespace", namespace);
		model.addAttribute("rows", resources.list(clusterId, descriptor, namespace));
		return "resource";
	}

	@GetMapping("/clusters/{clusterId}/events")
	public String events(@PathVariable String clusterId, @RequestParam(required = false) String namespace,
			Model model) {
		shell(model, clusterId, "events");
		model.addAttribute("namespace", namespace);
		model.addAttribute("events", events.list(clusterId, namespace));
		return "events";
	}

	@GetMapping("/clusters/{clusterId}/helm")
	public String helm(@PathVariable String clusterId, @RequestParam(required = false) String namespace, Model model) {
		shell(model, clusterId, "helm");
		model.addAttribute("namespace", namespace);
		model.addAttribute("releases", helm.listReleases(clusterId, namespace));
		return "helm";
	}

	@GetMapping("/clusters/{clusterId}/metrics")
	public String metrics(@PathVariable String clusterId, @RequestParam(required = false) String namespace,
			Model model) {
		shell(model, clusterId, "metrics");
		model.addAttribute("namespace", namespace);
		model.addAttribute("nodeUsage", metrics.nodeUsage(clusterId));
		model.addAttribute("podUsage", metrics.podUsage(clusterId, namespace));
		return "metrics";
	}

	@GetMapping("/clusters/{clusterId}/pods/{namespace}/{pod}/logs")
	public String logs(@PathVariable String clusterId, @PathVariable String namespace, @PathVariable String pod,
			@RequestParam(required = false) String container, Model model) {
		shell(model, clusterId, "pods");
		model.addAttribute("namespace", namespace);
		model.addAttribute("pod", pod);
		model.addAttribute("container", container);
		return "logs";
	}

	/** Populate the model attributes the in-cluster shell (left nav) needs. */
	private void shell(Model model, String clusterId, String selectedId) {
		model.addAttribute("clusterId", clusterId);
		model.addAttribute("cluster", clusters.info(clusterId).orElse(null));
		model.addAttribute("categories", navCatalog.categories());
		model.addAttribute("selectedId", selectedId);
	}

}
