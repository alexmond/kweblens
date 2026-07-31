package org.alexmond.kweblens.cluster;

import java.util.List;

/**
 * What a cluster's configuration looks like from outside — everything the editor needs to
 * render, and nothing that could authenticate anyone.
 *
 * <p>
 * <b>There is deliberately no kubeconfig field.</b> The credential goes in and never
 * comes back out: {@link #kubeconfigStored} says only <em>whether</em> one is held, which
 * is the same "report the presence, never the value" rule the diagnostics panel follows.
 * A read-only GET that returned the YAML would turn any open-mode deployment into a
 * credential-exfiltration endpoint.
 *
 * @param id stable identifier
 * @param name display name
 * @param origin runtime-managed (editable) or declared elsewhere
 * @param masterUrl the API server the client is pointed at
 * @param context the selected kubeconfig context, if any
 * @param contexts every context available in the stored kubeconfig
 * @param kubeconfigStored whether kweblens holds a credential for this cluster
 * @param storage where runtime clusters are persisted, for the operator's benefit
 * @param persistent false when runtime clusters will not survive a restart
 */
public record ClusterConfigView(String id, String name, ClusterOrigin origin, String masterUrl, String context,
		List<String> contexts, boolean kubeconfigStored, String storage, boolean persistent) {
}
