package org.alexmond.kweblens.web.ui;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import org.alexmond.kweblens.cluster.ClusterRegistry;
import org.alexmond.kweblens.event.EventService;
import org.alexmond.kweblens.metric.MetricService;
import org.alexmond.kweblens.resource.ResourceDescriptor;
import org.alexmond.kweblens.resource.ResourceService;
import org.alexmond.kweblens.resource.ResourceSummary;
import org.alexmond.kweblens.web.ai.DiagnoseService;
import org.alexmond.kweblens.web.ai.RemediationService;
import org.alexmond.kweblens.web.helm.HelmService;
import org.alexmond.kweblens.web.nav.ClusterNavService;
import org.alexmond.kweblens.web.security.AuditService;

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

	private final DiagnoseService diagnose;

	private final RemediationService remediation;

	private final AuditService audit;

	private final ClusterNavService clusterNav;

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
		ResourceDescriptor descriptor = clusterNav.find(clusterId, resourceId)
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

	@GetMapping("/clusters/{clusterId}/diagnose")
	public String diagnose(@PathVariable String clusterId, @RequestParam(required = false) String namespace,
			Model model) {
		shell(model, clusterId, "diagnose");
		model.addAttribute("namespace", namespace);
		model.addAttribute("diagnosis", diagnose.diagnose(clusterId, namespace));
		model.addAttribute("proposals", remediation.propose(clusterId, namespace));
		return "diagnose";
	}

	@PostMapping("/clusters/{clusterId}/remediate")
	public String remediate(@PathVariable String clusterId, @RequestParam String namespace, @RequestParam String action,
			@RequestParam String target, Model model) {
		// Reached only via the confirm form; the security gate already required auth for
		// this POST.
		remediation.apply(clusterId, namespace, action, target, true);
		return "redirect:/clusters/" + clusterId + "/diagnose?namespace=" + namespace;
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

	@GetMapping("/clusters/{clusterId}/pods/{namespace}/{pod}/exec")
	public String exec(@PathVariable String clusterId, @PathVariable String namespace, @PathVariable String pod,
			@RequestParam(required = false) String container, Model model) {
		shell(model, clusterId, "pods");
		model.addAttribute("namespace", namespace);
		model.addAttribute("pod", pod);
		model.addAttribute("container", container);
		return "exec";
	}

	@GetMapping("/clusters/{clusterId}/detail")
	public String detail(@PathVariable String clusterId, @RequestParam String resource,
			@RequestParam(required = false) String namespace, @RequestParam String name, Model model) {
		ResourceDescriptor descriptor = clusterNav.find(clusterId, resource)
			.orElseThrow(() -> new UnknownResourceException(resource));
		shell(model, clusterId, resource);
		model.addAttribute("resource", resource);
		model.addAttribute("namespace", namespace);
		model.addAttribute("name", name);
		model.addAttribute("descriptor", descriptor);
		model.addAttribute("detail", resources.detail(clusterId, descriptor, namespace, name).orElse(null));
		model.addAttribute("events", events.listForObject(clusterId, namespace, descriptor.kind(), name));
		return "detail";
	}

	@GetMapping("/clusters/{clusterId}/yaml")
	public String yaml(@PathVariable String clusterId, @RequestParam String resource,
			@RequestParam(required = false) String namespace, @RequestParam String name, Model model) {
		ResourceDescriptor descriptor = clusterNav.find(clusterId, resource)
			.orElseThrow(() -> new UnknownResourceException(resource));
		shell(model, clusterId, resource);
		model.addAttribute("resource", resource);
		model.addAttribute("namespace", namespace);
		model.addAttribute("name", name);
		model.addAttribute("descriptor", descriptor);
		model.addAttribute("yaml", resources.getYaml(clusterId, descriptor, namespace, name));
		return "yaml";
	}

	@PostMapping("/clusters/{clusterId}/resource-action")
	public String resourceAction(@PathVariable String clusterId, @RequestParam String action,
			@RequestParam String resource, @RequestParam(required = false) String namespace, @RequestParam String name,
			@RequestParam(required = false) Integer replicas) {
		ResourceDescriptor descriptor = clusterNav.find(clusterId, resource)
			.orElseThrow(() -> new UnknownResourceException(resource));
		switch (action) {
			case "delete" -> resources.delete(clusterId, descriptor, namespace, name);
			case "scale" -> resources.scale(clusterId, descriptor, namespace, name, (replicas != null) ? replicas : 0);
			case "restart" -> resources.rolloutRestart(clusterId, descriptor, namespace, name);
			default -> throw new IllegalArgumentException("Unknown action: " + action);
		}
		audit.record(clusterId, action, descriptor.kind() + "/" + namespace + "/" + name);
		if ("delete".equals(action)) {
			String query = (namespace != null && !namespace.isBlank()) ? "?namespace=" + namespace : "";
			return "redirect:/clusters/" + clusterId + "/r/" + resource + query;
		}
		return "redirect:/clusters/" + clusterId + "/detail?resource=" + resource + "&namespace="
				+ ((namespace != null) ? namespace : "") + "&name=" + name;
	}

	@PostMapping("/clusters/{clusterId}/apply")
	public String apply(@PathVariable String clusterId, @RequestParam String manifest, @RequestParam String resource,
			@RequestParam(required = false) String namespace) {
		ResourceSummary applied = resources.apply(clusterId, manifest);
		audit.record(clusterId, "apply", applied.kind() + "/" + applied.namespace() + "/" + applied.name());
		String query = (namespace != null && !namespace.isBlank()) ? "?namespace=" + namespace : "";
		return "redirect:/clusters/" + clusterId + "/r/" + resource + query;
	}

	/**
	 * Populate the model attributes the in-cluster shell (cluster rail + left nav) needs.
	 */
	private void shell(Model model, String clusterId, String selectedId) {
		model.addAttribute("clusterId", clusterId);
		model.addAttribute("cluster", clusters.info(clusterId).orElse(null));
		model.addAttribute("allClusters", clusters.list());
		model.addAttribute("categories", clusterNav.categories(clusterId));
		model.addAttribute("selectedId", selectedId);
	}

}
