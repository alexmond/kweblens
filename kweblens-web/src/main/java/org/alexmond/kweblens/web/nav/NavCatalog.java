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

	private final List<NavCategory> categories = List.of(
			new NavCategory("Cluster", "bi-diagram-3",
					List.of(ResourceDescriptor.coreCluster("nodes", "Nodes", "Node", "nodes"))),
			new NavCategory("Workloads", "bi-box-seam", List.of(
					ResourceDescriptor.coreNamespaced("pods", "Pods", "Pod", "pods"),
					ResourceDescriptor.namespaced("deployments", "Deployments", "Deployment", APPS, "v1",
							"deployments"),
					ResourceDescriptor.namespaced("statefulsets", "Stateful Sets", "StatefulSet", APPS, "v1",
							"statefulsets"),
					ResourceDescriptor.namespaced("daemonsets", "Daemon Sets", "DaemonSet", APPS, "v1", "daemonsets"),
					ResourceDescriptor.namespaced("replicasets", "Replica Sets", "ReplicaSet", APPS, "v1",
							"replicasets"),
					ResourceDescriptor.namespaced("jobs", "Jobs", "Job", BATCH, "v1", "jobs"),
					ResourceDescriptor.namespaced("cronjobs", "Cron Jobs", "CronJob", BATCH, "v1", "cronjobs"))),
			new NavCategory("Config", "bi-sliders",
					List.of(ResourceDescriptor.coreNamespaced("configmaps", "Config Maps", "ConfigMap", "configmaps"),
							ResourceDescriptor.coreNamespaced("secrets", "Secrets", "Secret", "secrets"))),
			new NavCategory("Network", "bi-diagram-2",
					List.of(ResourceDescriptor.coreNamespaced("services", "Services", "Service", "services"),
							ResourceDescriptor.namespaced("ingresses", "Ingresses", "Ingress", "networking.k8s.io",
									"v1", "ingresses"))),
			new NavCategory("Storage", "bi-hdd-stack",
					List.of(ResourceDescriptor.coreNamespaced("persistentvolumeclaims", "Persistent Volume Claims",
							"PersistentVolumeClaim", "persistentvolumeclaims"),
							ResourceDescriptor.coreCluster("persistentvolumes", "Persistent Volumes",
									"PersistentVolume", "persistentvolumes"),
							ResourceDescriptor.cluster("storageclasses", "Storage Classes", "StorageClass",
									"storage.k8s.io", "v1", "storageclasses"))),
			new NavCategory("Namespaces", "bi-collection",
					List.of(ResourceDescriptor.coreCluster("namespaces", "Namespaces", "Namespace", "namespaces"))),
			new NavCategory("Events", "bi-clock-history",
					List.of(ResourceDescriptor.coreNamespaced("events", "Events", "Event", "events"))),
			new NavCategory("Access Control", "bi-shield-lock",
					List.of(ResourceDescriptor.coreNamespaced("serviceaccounts", "Service Accounts", "ServiceAccount",
							"serviceaccounts"),
							ResourceDescriptor.cluster("clusterroles", "Cluster Roles", "ClusterRole", RBAC, "v1",
									"clusterroles"),
							ResourceDescriptor.namespaced("roles", "Roles", "Role", RBAC, "v1", "roles"),
							ResourceDescriptor.cluster("clusterrolebindings", "Cluster Role Bindings",
									"ClusterRoleBinding", RBAC, "v1", "clusterrolebindings"),
							ResourceDescriptor.namespaced("rolebindings", "Role Bindings", "RoleBinding", RBAC, "v1",
									"rolebindings"))));

	/** The category tree for the left navigation. */
	public List<NavCategory> categories() {
		return categories;
	}

	/** Resolve a route id back to its descriptor. */
	public Optional<ResourceDescriptor> find(String id) {
		return categories.stream().flatMap((c) -> c.items().stream()).filter((d) -> d.id().equals(id)).findFirst();
	}

}
