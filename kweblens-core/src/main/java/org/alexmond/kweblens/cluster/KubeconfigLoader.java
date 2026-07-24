package org.alexmond.kweblens.cluster;

import java.util.List;
import java.util.Objects;

import io.fabric8.kubernetes.api.model.NamedContext;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.fabric8.kubernetes.client.utils.Serialization;

/**
 * Reads kubeconfig YAML into the pieces the {@link ClusterRegistry} needs: the list of
 * contexts, the current context, and a {@link KubernetesClient} pointed at a chosen
 * context. Building a client does not connect, so none of this reaches a cluster.
 */
public final class KubeconfigLoader {

	private KubeconfigLoader() {
	}

	/** Every context name in the kubeconfig, in file order. */
	public static List<String> contexts(String kubeconfigYaml) {
		io.fabric8.kubernetes.api.model.Config parsed = parse(kubeconfigYaml);
		if (parsed == null || parsed.getContexts() == null) {
			return List.of();
		}
		return parsed.getContexts().stream().map(NamedContext::getName).filter(Objects::nonNull).toList();
	}

	/** The kubeconfig's {@code current-context}, or null if unset. */
	public static String currentContext(String kubeconfigYaml) {
		io.fabric8.kubernetes.api.model.Config parsed = parse(kubeconfigYaml);
		return (parsed != null) ? parsed.getCurrentContext() : null;
	}

	/**
	 * Build a client for one context of a kubeconfig.
	 * @param kubeconfigYaml the kubeconfig contents
	 * @param path the on-disk path (used to resolve relative cert paths; may be null)
	 * @param context the context to select; null selects the current-context
	 */
	public static KubernetesClient clientFor(String kubeconfigYaml, String path, String context) {
		Config config = Config.fromKubeconfig(context, kubeconfigYaml, path);
		return new KubernetesClientBuilder().withConfig(config).build();
	}

	private static io.fabric8.kubernetes.api.model.Config parse(String kubeconfigYaml) {
		if (kubeconfigYaml == null || kubeconfigYaml.isBlank()) {
			return null;
		}
		return Serialization.unmarshal(kubeconfigYaml, io.fabric8.kubernetes.api.model.Config.class);
	}

}
