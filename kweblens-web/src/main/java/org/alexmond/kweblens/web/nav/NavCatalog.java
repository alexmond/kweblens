package org.alexmond.kweblens.web.nav;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Component;

import org.alexmond.kweblens.resource.ResourceDescriptor;

/**
 * The static left-navigation registry: categories → kinds. This is the single source that
 * drives both the rendered menu and the resource routes
 * ({@code /clusters/{cluster}/r/{id}}), so adding a built-in kind is one line here. The
 * dynamic Custom Resources section (CRD-discovered) is layered on later (issue #12); Helm
 * and Access Control write surfaces arrive with their own milestones.
 */
@Component
public class NavCatalog {

	private static final String APPS = "apps";

	private static final String BATCH = "batch";

	private static final String RBAC = "rbac.authorization.k8s.io";

	private static final String NETWORKING = "networking.k8s.io";

	private static final String ADMISSION = "admissionregistration.k8s.io";

	private final List<NavCategory> categories = List.of(
			new NavCategory("Cluster", "bi-diagram-3",
					List.of(ResourceDescriptor.coreCluster("nodes", "Nodes", "Node", "nodes"),
							ResourceDescriptor.coreCluster("namespaces", "Namespaces", "Namespace", "namespaces"),
							ResourceDescriptor.coreNamespaced("events", "Events", "Event", "events"))),
			new NavCategory("Workloads", "bi-box-seam", List.of(
					ResourceDescriptor.coreNamespaced("pods", "Pods", "Pod", "pods"),
					ResourceDescriptor.namespaced("deployments", "Deployments", "Deployment", APPS, "v1", "deployments")
						.asExpandable(),
					ResourceDescriptor
						.namespaced("statefulsets", "Stateful Sets", "StatefulSet", APPS, "v1", "statefulsets")
						.asExpandable(),
					ResourceDescriptor.namespaced("daemonsets", "Daemon Sets", "DaemonSet", APPS, "v1", "daemonsets")
						.asExpandable(),
					ResourceDescriptor
						.namespaced("replicasets", "Replica Sets", "ReplicaSet", APPS, "v1", "replicasets")
						.asExpandable(),
					ResourceDescriptor.namespaced("jobs", "Jobs", "Job", BATCH, "v1", "jobs"),
					ResourceDescriptor.namespaced("cronjobs", "Cron Jobs", "CronJob", BATCH, "v1", "cronjobs"),
					ResourceDescriptor.coreNamespaced("replicationcontrollers", "Replication Controllers",
							"ReplicationController", "replicationcontrollers"))),
			new NavCategory("Config", "bi-sliders", List.of(
					ResourceDescriptor.coreNamespaced("configmaps", "Config Maps", "ConfigMap", "configmaps"),
					ResourceDescriptor.coreNamespaced("secrets", "Secrets", "Secret", "secrets"),
					ResourceDescriptor.coreNamespaced("resourcequotas", "Resource Quotas", "ResourceQuota",
							"resourcequotas"),
					ResourceDescriptor.coreNamespaced("limitranges", "Limit Ranges", "LimitRange", "limitranges"),
					ResourceDescriptor.namespaced("horizontalpodautoscalers", "Horizontal Pod Autoscalers",
							"HorizontalPodAutoscaler", "autoscaling", "v2", "horizontalpodautoscalers"),
					ResourceDescriptor.namespaced("poddisruptionbudgets", "Pod Disruption Budgets",
							"PodDisruptionBudget", "policy", "v1", "poddisruptionbudgets"),
					ResourceDescriptor.cluster("priorityclasses", "Priority Classes", "PriorityClass",
							"scheduling.k8s.io", "v1", "priorityclasses"),
					ResourceDescriptor.cluster("runtimeclasses", "Runtime Classes", "RuntimeClass", "node.k8s.io", "v1",
							"runtimeclasses"),
					ResourceDescriptor.namespaced("leases", "Leases", "Lease", "coordination.k8s.io", "v1", "leases"),
					ResourceDescriptor.cluster("mutatingwebhookconfigurations", "Mutating Webhook Configs",
							"MutatingWebhookConfiguration", ADMISSION, "v1", "mutatingwebhookconfigurations"),
					ResourceDescriptor.cluster("validatingwebhookconfigurations", "Validating Webhook Configs",
							"ValidatingWebhookConfiguration", ADMISSION, "v1", "validatingwebhookconfigurations"),
					ResourceDescriptor.cluster("validatingadmissionpolicies", "Validating Admission Policies",
							"ValidatingAdmissionPolicy", ADMISSION, "v1", "validatingadmissionpolicies"),
					ResourceDescriptor.cluster("validatingadmissionpolicybindings",
							"Validating Admission Policy Bindings", "ValidatingAdmissionPolicyBinding", ADMISSION, "v1",
							"validatingadmissionpolicybindings"))),
			new NavCategory("Network", "bi-diagram-2", List.of(
					ResourceDescriptor.coreNamespaced("services", "Services", "Service", "services"),
					ResourceDescriptor.namespaced("ingresses", "Ingresses", "Ingress", NETWORKING, "v1", "ingresses"),
					ResourceDescriptor.namespaced("endpointslices", "Endpoint Slices", "EndpointSlice",
							"discovery.k8s.io", "v1", "endpointslices"),
					ResourceDescriptor.coreNamespaced("endpoints", "Endpoints", "Endpoints", "endpoints"),
					ResourceDescriptor.cluster("ingressclasses", "Ingress Classes", "IngressClass", NETWORKING, "v1",
							"ingressclasses"),
					ResourceDescriptor.namespaced("networkpolicies", "Network Policies", "NetworkPolicy", NETWORKING,
							"v1", "networkpolicies"))),
			new NavCategory("Storage", "bi-hdd-stack",
					List.of(ResourceDescriptor.coreNamespaced("persistentvolumeclaims", "Persistent Volume Claims",
							"PersistentVolumeClaim", "persistentvolumeclaims"),
							ResourceDescriptor.coreCluster("persistentvolumes", "Persistent Volumes",
									"PersistentVolume", "persistentvolumes"),
							ResourceDescriptor.cluster("storageclasses", "Storage Classes", "StorageClass",
									"storage.k8s.io", "v1", "storageclasses"))),
			new NavCategory("Access Control", "bi-shield-lock",
					List.of(ResourceDescriptor.coreNamespaced("serviceaccounts", "Service Accounts", "ServiceAccount",
							"serviceaccounts"),
							ResourceDescriptor.cluster("clusterroles", "Cluster Roles", "ClusterRole", RBAC, "v1",
									"clusterroles"),
							ResourceDescriptor.namespaced("roles", "Roles", "Role", RBAC, "v1", "roles"),
							ResourceDescriptor.cluster("clusterrolebindings", "Cluster Role Bindings",
									"ClusterRoleBinding", RBAC, "v1", "clusterrolebindings"),
							ResourceDescriptor.namespaced("rolebindings", "Role Bindings", "RoleBinding", RBAC, "v1",
									"rolebindings"))),
			new NavCategory("Custom Resources", "bi-puzzle",
					List.of(ResourceDescriptor.cluster("customresourcedefinitions", "Definitions",
							"CustomResourceDefinition", "apiextensions.k8s.io", "v1", "customresourcedefinitions"))));

	/** The category tree for the left navigation. */
	public List<NavCategory> categories() {
		return categories;
	}

	/** Resolve a route id back to its descriptor. */
	public Optional<ResourceDescriptor> find(String id) {
		return categories.stream().flatMap((c) -> c.items().stream()).filter((d) -> d.id().equals(id)).findFirst();
	}

}
