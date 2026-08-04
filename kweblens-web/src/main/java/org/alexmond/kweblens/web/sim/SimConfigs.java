package org.alexmond.kweblens.web.sim;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;

/**
 * ConfigMaps and Secrets: the two kinds that dominate a real cluster's payload and were
 * the smallest objects in the rig.
 *
 * <p>
 * Their sizes come from {@link SimPayloads}; what this adds is the rest of the object —
 * and one detail that is easy to miss and matters to size: <b>managedFields name every
 * data key individually</b>. A ConfigMap with six keys carries six more paths than one
 * with one, which is part of why real ConfigMaps are never as small as their data alone
 * suggests.
 *
 * <p>
 * The Secret {@code type} varies too. A cluster's Secrets are mostly
 * {@code kubernetes.io/service-account-token}, {@code kubernetes.io/tls} and
 * {@code helm.sh/release.v1}, and the last of those is the one that carries the 673 KB
 * value — a rig where every Secret is {@code Opaque} cannot show the drawer rendering the
 * type column, nor the size distribution that goes with it.
 */
final class SimConfigs {

	private SimConfigs() {
	}

	static ConfigMap configMap(int index, String namespace, double payloadScale) {
		Map<String, String> data = SimPayloads.configMapData(index, payloadScale);
		Map<String, String> labels = SimMeta.appLabels(index, "config");
		Map<String, String> annotations = SimMeta.commonAnnotations(index, "ConfigMap", namespace);
		ObjectMeta meta = SimMeta.meta("ConfigMap", index, "sim-config-" + index, namespace, labels, annotations);
		meta.setManagedFields(List
			.of(SimFields.entry("helm", "Update", SimMeta.created("mf", index), paths(labels, annotations, data))));
		return new ConfigMapBuilder().withMetadata(meta).withData(data).withImmutable(false).build();
	}

	static Secret secret(int index, String namespace, double payloadScale) {
		Map<String, String> data = SimPayloads.secretData(index, payloadScale);
		Map<String, String> labels = SimMeta.appLabels(index, "config");
		Map<String, String> annotations = SimMeta.commonAnnotations(index, "Secret", namespace);
		ObjectMeta meta = SimMeta.meta("Secret", index, "sim-secret-" + index, namespace, labels, annotations);
		meta.setManagedFields(List
			.of(SimFields.entry("helm", "Update", SimMeta.created("mf", index), paths(labels, annotations, data))));
		return new SecretBuilder().withMetadata(meta).withType(type(index)).withData(data).build();
	}

	/**
	 * The Secret's type, following the same roll its size does — the enormous ones are
	 * Helm releases, which is why they are enormous.
	 */
	static String type(int index) {
		int roll = SimPayloads.roll("secret-roll", index);
		if (roll >= 97) {
			return "helm.sh/release.v1";
		}
		return (roll < 55) ? "kubernetes.io/service-account-token" : "kubernetes.io/tls";
	}

	private static List<String> paths(Map<String, String> labels, Map<String, String> annotations,
			Map<String, String> data) {
		List<String> paths = new ArrayList<>(SimFields.metadataPaths(labels, annotations));
		paths.add("data|.");
		data.keySet().forEach((key) -> paths.add("data|" + key));
		paths.add("type");
		return paths;
	}

}
