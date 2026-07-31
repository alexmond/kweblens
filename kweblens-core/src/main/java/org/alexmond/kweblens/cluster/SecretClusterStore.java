package org.alexmond.kweblens.cluster;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.NonDeletingOperation;
import lombok.extern.slf4j.Slf4j;

/**
 * Persists runtime clusters as Kubernetes Secrets in the namespace kweblens runs in — one
 * Secret per cluster, named {@code <prefix><id>}, labelled so they can be listed back.
 *
 * <p>
 * This is the in-cluster answer to "where does a kubeconfig live": a Secret inherits the
 * cluster's encryption-at-rest, its RBAC, and its backup story, none of which a file in a
 * container's ephemeral filesystem does. It also survives a pod reschedule without
 * needing a PVC, which is the difference between "add a cluster" working and "add a
 * cluster" quietly resetting the next time the deployment rolls.
 *
 * <p>
 * <b>The client here is not a registry client.</b> The {@link ClusterRegistry} owns
 * clients for the clusters kweblens <em>browses</em>; this one is kweblens's handle on
 * its own storage in the cluster it is <em>running in</em>. They are different concerns
 * and conflating them would make the store's lifetime depend on whichever browse-target
 * happened to be registered. The store closes its own client.
 */
@Slf4j
public class SecretClusterStore implements ClusterStore, AutoCloseable {

	/** Marks the Secrets this store owns, so listing cannot pick up unrelated ones. */
	private static final String MANAGED_BY_LABEL = "app.kubernetes.io/managed-by";

	private static final String MANAGED_BY = "kweblens";

	private static final String ID_LABEL = "kweblens.alexmond.org/cluster-id";

	private static final String KEY_KUBECONFIG = "kubeconfig";

	private static final String KEY_NAME = "name";

	private static final String KEY_CONTEXT = "context";

	private final KubernetesClient client;

	private final String namespace;

	private final String prefix;

	public SecretClusterStore(KubernetesClient client, String namespace, String prefix) {
		this.client = client;
		this.namespace = namespace;
		this.prefix = prefix;
	}

	@Override
	public List<ClusterDefinition> load() {
		List<Secret> secrets = this.client.secrets()
			.inNamespace(this.namespace)
			.withLabel(MANAGED_BY_LABEL, MANAGED_BY)
			.list()
			.getItems();
		return secrets.stream()
			.map(this::toDefinition)
			.filter(Objects::nonNull)
			.sorted((a, b) -> a.id().compareTo(b.id()))
			.toList();
	}

	@Override
	public void save(ClusterDefinition definition) {
		Map<String, String> data = new LinkedHashMap<>();
		data.put(KEY_KUBECONFIG, encode(definition.kubeconfig()));
		data.put(KEY_NAME, encode((definition.name() != null) ? definition.name() : definition.id()));
		data.put(KEY_CONTEXT, encode((definition.context() != null) ? definition.context() : ""));
		Secret secret = new SecretBuilder().withNewMetadata()
			.withName(secretName(definition.id()))
			.withNamespace(this.namespace)
			.withLabels(Map.of(MANAGED_BY_LABEL, MANAGED_BY, ID_LABEL, definition.id()))
			.endMetadata()
			.withType("Opaque")
			.withData(data)
			.build();
		// createOr(update): a plain create fails once the Secret exists, and server-side
		// apply is not universally available on older API servers.
		this.client.secrets().inNamespace(this.namespace).resource(secret).createOr(NonDeletingOperation::update);
	}

	@Override
	public void delete(String id) {
		this.client.secrets().inNamespace(this.namespace).withName(secretName(id)).delete();
	}

	@Override
	public String describe() {
		return "Kubernetes Secrets " + this.namespace + "/" + this.prefix + "*";
	}

	@Override
	public void close() {
		this.client.close();
	}

	private String secretName(String id) {
		return this.prefix + id;
	}

	/**
	 * A Secret whose id label is missing, or whose payload is unreadable, is skipped
	 * rather than failing the whole listing — one hand-edited Secret must not stop
	 * kweblens starting.
	 */
	private ClusterDefinition toDefinition(Secret secret) {
		Map<String, String> labels = (secret.getMetadata() != null) ? secret.getMetadata().getLabels() : null;
		String id = (labels != null) ? labels.get(ID_LABEL) : null;
		if (id == null || id.isBlank()) {
			log.warn("Ignoring kweblens-managed Secret with no {} label", ID_LABEL);
			return null;
		}
		Map<String, String> data = (secret.getData() != null) ? secret.getData() : Map.of();
		String context = decode(data.get(KEY_CONTEXT));
		return new ClusterDefinition(id, orDefault(decode(data.get(KEY_NAME)), id),
				(context != null && !context.isBlank()) ? context : null, decode(data.get(KEY_KUBECONFIG)));
	}

	private String orDefault(String value, String fallback) {
		return (value != null && !value.isBlank()) ? value : fallback;
	}

	private String encode(String value) {
		return Base64.getEncoder().encodeToString(((value != null) ? value : "").getBytes(StandardCharsets.UTF_8));
	}

	private String decode(String value) {
		if (value == null) {
			return null;
		}
		return new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
	}

}
