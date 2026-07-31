package org.alexmond.kweblens.cluster;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;

import io.fabric8.kubernetes.client.KubernetesClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;

/**
 * Add, edit and remove clusters at runtime — the write side of the
 * {@link ClusterRegistry}, which until now could only be populated at startup from
 * {@code kweblens.clusters[*]} and the ambient kubeconfig.
 *
 * <p>
 * <b>Order of operations is the whole design.</b> A definition is validated and turned
 * into a working client <em>before</em> anything is persisted or registered, so a
 * rejected kubeconfig leaves both the store and the registry byte-for-byte as they were.
 * The alternative — register, then discover the config is broken — would leave the
 * cluster rail advertising a cluster that 500s on every click, and (worse) would have
 * already closed the client of any cluster it replaced.
 *
 * <p>
 * <b>Validation is structural, not a connection test.</b> Building a fabric8 client does
 * not connect, so "is this kubeconfig usable" can only be answered as far as "does it
 * parse, does it name the context you asked for". Reachability is a separate question
 * with its own failure modes (the cluster may legitimately be down when it is added), and
 * conflating them would make adding a cluster impossible during an outage.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ClusterConfigService {

	/**
	 * An id becomes a URL path segment, a filename and a Secret name suffix, so it is
	 * constrained to the intersection of what all three accept.
	 */
	private static final Pattern SAFE_ID = Pattern.compile("[a-z0-9][a-z0-9._-]{0,62}");

	/** How much of a client-construction failure is safe to echo back. */
	private static final int MAX_DETAIL = 200;

	private final ClusterRegistry registry;

	private final ClusterStore store;

	// A ReentrantLock rather than a synchronized block: the check-then-act across the
	// registry and the store must be atomic, and the project's PMD ruleset rejects
	// intrinsic locks.
	private final ReentrantLock lock = new ReentrantLock();

	/**
	 * Register everything the store holds. Called once at startup, after the statically
	 * declared clusters, so a runtime cluster can never silently shadow one the
	 * deployment manifest asked for.
	 * @return how many runtime clusters were restored
	 */
	public int restore() {
		List<ClusterDefinition> persisted;
		try {
			persisted = this.store.load();
		}
		catch (RuntimeException ex) {
			// An unreadable store must not stop kweblens booting. In-cluster this is the
			// likely shape of a missing RBAC grant on Secrets, and the right outcome
			// there
			// is "runtime clusters unavailable", not "the dashboard is down".
			log.warn("Could not read the cluster store ({}) — no runtime clusters restored: {}", this.store.describe(),
					ex.getMessage());
			return 0;
		}
		int restored = 0;
		for (ClusterDefinition definition : persisted) {
			if (this.registry.info(definition.id()).isPresent()) {
				log.warn("Persisted cluster '{}' is also declared in configuration — keeping the declared one",
						definition.id());
				continue;
			}
			try {
				this.registry.register(definition.id(), displayName(definition), clientFor(definition),
						ClusterOrigin.RUNTIME);
				restored++;
			}
			catch (RuntimeException ex) {
				// Never fail startup for one bad stored definition: the operator needs
				// kweblens up in order to fix or delete it.
				log.warn("Could not restore persisted cluster '{}': {}", definition.id(), ex.getMessage());
			}
		}
		return restored;
	}

	/** Add a new runtime cluster: validate, connect-config, persist, register. */
	public ClusterInfo add(ClusterDefinition definition) {
		this.lock.lock();
		try {
			ClusterDefinition normalized = validate(definition);
			if (this.registry.info(normalized.id()).isPresent()) {
				throw new ClusterConflictException("A cluster with id '" + normalized.id() + "' is already registered");
			}
			KubernetesClient client = clientFor(normalized);
			this.store.save(normalized);
			return this.registry.register(normalized.id(), displayName(normalized), client, ClusterOrigin.RUNTIME);
		}
		finally {
			this.lock.unlock();
		}
	}

	/**
	 * Edit a runtime cluster. A blank {@code kubeconfig} keeps the stored credential, so
	 * renaming or switching context does not require the operator to paste the kubeconfig
	 * again — which in practice is what makes people paste the wrong one.
	 */
	public ClusterInfo update(String id, ClusterDefinition patch) {
		this.lock.lock();
		try {
			requireEditable(id);
			ClusterDefinition existing = this.store.find(id)
				.orElseThrow(() -> new ClusterConflictException("No stored definition for cluster '" + id + "'"));
			String kubeconfig = (patch != null && patch.hasKubeconfig()) ? patch.kubeconfig() : existing.kubeconfig();
			String name = (patch != null && hasText(patch.name())) ? patch.name() : existing.name();
			String context = (patch != null && hasText(patch.context())) ? patch.context() : existing.context();
			ClusterDefinition merged = validate(new ClusterDefinition(id, name, context, kubeconfig));
			KubernetesClient client = clientFor(merged);
			this.store.save(merged);
			// register() closes the previous client for this id.
			return this.registry.register(id, displayName(merged), client, ClusterOrigin.RUNTIME);
		}
		finally {
			this.lock.unlock();
		}
	}

	/** Remove a runtime cluster: close its client, then forget the credential. */
	public void remove(String id) {
		this.lock.lock();
		try {
			requireEditable(id);
			this.registry.unregister(id);
			this.store.delete(id);
		}
		finally {
			this.lock.unlock();
		}
	}

	/** The editor's view of one cluster — never its credential. */
	public ClusterConfigView describe(String id) {
		ClusterInfo info = this.registry.info(id).orElseThrow(() -> new UnknownClusterException(id));
		Optional<ClusterDefinition> stored = (info.origin() == ClusterOrigin.RUNTIME) ? this.store.find(id)
				: Optional.empty();
		String kubeconfig = stored.map(ClusterDefinition::kubeconfig).orElse(null);
		return new ClusterConfigView(id, info.name(), info.origin(), info.masterUrl(),
				stored.map(ClusterDefinition::context).orElse(null), KubeconfigLoader.contexts(kubeconfig),
				stored.map(ClusterDefinition::hasKubeconfig).orElse(false), this.store.describe(),
				this.store.persistent());
	}

	/**
	 * The contexts in a kubeconfig the caller is holding but has not committed, so the
	 * editor can offer them for selection. Nothing is stored and nothing is registered.
	 */
	public List<String> inspect(String kubeconfigYaml) {
		List<String> contexts = KubeconfigLoader.contexts(kubeconfigYaml);
		if (contexts.isEmpty()) {
			throw new InvalidClusterException("That kubeconfig contains no contexts");
		}
		return contexts;
	}

	private void requireEditable(String id) {
		ClusterInfo info = this.registry.info(id).orElseThrow(() -> new UnknownClusterException(id));
		if (info.origin() != ClusterOrigin.RUNTIME) {
			throw new ClusterConflictException("Cluster '" + id
					+ "' is declared in configuration or the ambient kubeconfig, so it cannot be edited at runtime; "
					+ "change kweblens.clusters[*] or the kubeconfig instead");
		}
	}

	/**
	 * Structural validation. Everything here fails the request outright, before any state
	 * changes.
	 */
	private ClusterDefinition validate(ClusterDefinition definition) {
		if (definition == null) {
			throw new InvalidClusterException("No cluster definition supplied");
		}
		String id = (definition.id() != null) ? definition.id().trim().toLowerCase(Locale.ROOT) : null;
		if (id == null || !SAFE_ID.matcher(id).matches()) {
			throw new InvalidClusterException("Invalid cluster id '" + definition.id()
					+ "' — use lowercase letters, digits, '.', '_' or '-', starting with a letter or digit "
					+ "(up to 63 characters)");
		}
		if (!definition.hasKubeconfig()) {
			throw new InvalidClusterException("A kubeconfig is required to add a cluster");
		}
		List<String> contexts = contextsOrReject(definition.kubeconfig());
		String context = hasText(definition.context()) ? definition.context().trim() : null;
		if (context != null && !contexts.contains(context)) {
			throw new InvalidClusterException("Context '" + context + "' is not in that kubeconfig (it has: "
					+ String.join(", ", contexts) + ")");
		}
		if (context == null && !hasText(KubeconfigLoader.currentContext(definition.kubeconfig()))) {
			throw new InvalidClusterException("That kubeconfig has no current-context, so a context must be chosen "
					+ "(it has: " + String.join(", ", contexts) + ")");
		}
		String name = hasText(definition.name()) ? definition.name().trim() : id;
		return new ClusterDefinition(id, name, context, definition.kubeconfig());
	}

	private List<String> contextsOrReject(String kubeconfigYaml) {
		List<String> contexts;
		try {
			contexts = KubeconfigLoader.contexts(kubeconfigYaml);
		}
		catch (RuntimeException ex) {
			// The cause is kept for the server-side stack trace but its message is
			// deliberately NOT the API detail: a YAML parse error quotes the offending
			// document, which here is a credential.
			throw new InvalidClusterException("That kubeconfig could not be parsed as YAML", ex);
		}
		if (contexts.isEmpty()) {
			throw new InvalidClusterException("That kubeconfig contains no contexts");
		}
		return contexts;
	}

	/**
	 * Build the client for a definition. Relative certificate paths inside a
	 * runtime-supplied kubeconfig cannot be resolved (there is no file it came from), so
	 * runtime kubeconfigs are expected to embed their credentials.
	 */
	private KubernetesClient clientFor(ClusterDefinition definition) {
		try {
			return KubeconfigLoader.clientFor(definition.kubeconfig(), null, definition.context());
		}
		catch (RuntimeException ex) {
			throw new InvalidClusterException("That kubeconfig could not be used: " + firstLine(ex.getMessage()), ex);
		}
	}

	/**
	 * Only the first line of a failure message is echoed to the caller. Parser and
	 * decoder errors can quote the document they choked on, and the document here is a
	 * credential.
	 */
	private String firstLine(String message) {
		if (message == null || message.isBlank()) {
			return "it could not be turned into a client";
		}
		String line = message.lines().findFirst().orElse("").trim();
		return (line.length() > MAX_DETAIL) ? line.substring(0, MAX_DETAIL) + "…" : line;
	}

	private String displayName(ClusterDefinition definition) {
		return hasText(definition.name()) ? definition.name() : definition.id();
	}

	private boolean hasText(String value) {
		return value != null && !value.isBlank();
	}

}
