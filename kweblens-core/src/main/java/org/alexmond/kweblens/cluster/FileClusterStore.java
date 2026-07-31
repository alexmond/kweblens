package org.alexmond.kweblens.cluster;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import lombok.extern.slf4j.Slf4j;

/**
 * Persists runtime clusters in a data directory: one {@code .properties} file per cluster
 * for the display name and context, and a matching {@code .kubeconfig} file for the
 * credential, both named after the cluster id.
 *
 * <p>
 * The credential is deliberately a <b>separate file</b> rather than a field inside one
 * document, for two reasons: the file permissions that matter (owner-only) can be applied
 * to exactly the thing that needs them, and an operator inspecting the data directory can
 * see at a glance which files are secret. Both the directory and the kubeconfig are
 * created {@code 700}/{@code 600} where the filesystem supports POSIX permissions.
 *
 * <p>
 * Ids are validated by {@link ClusterConfigService} before they reach here and re-checked
 * on the way in, because an id becomes a filename: an unchecked one is a path-traversal
 * primitive.
 */
@Slf4j
public class FileClusterStore implements ClusterStore {

	/**
	 * Same shape as the id validation in {@link ClusterConfigService}, enforced twice.
	 */
	private static final Pattern SAFE_ID = Pattern.compile("[a-z0-9][a-z0-9._-]{0,62}");

	private static final String META_EXT = ".properties";

	private static final String CREDENTIAL_EXT = ".kubeconfig";

	private static final String KEY_NAME = "name";

	private static final String KEY_CONTEXT = "context";

	private final Path dir;

	public FileClusterStore(Path dir) {
		this.dir = dir;
	}

	@Override
	public List<ClusterDefinition> load() {
		if (!Files.isDirectory(this.dir)) {
			return List.of();
		}
		List<String> ids;
		try (Stream<Path> files = Files.list(this.dir)) {
			ids = files.map((p) -> p.getFileName().toString())
				.filter((n) -> n.endsWith(META_EXT))
				.map((n) -> n.substring(0, n.length() - META_EXT.length()))
				.sorted()
				.toList();
		}
		catch (IOException ex) {
			throw new UncheckedIOException("Could not list the cluster data directory " + this.dir, ex);
		}
		List<ClusterDefinition> out = new ArrayList<>();
		for (String id : ids) {
			readOne(id).ifPresent(out::add);
		}
		return List.copyOf(out);
	}

	@Override
	public void save(ClusterDefinition definition) {
		String id = validated(definition.id());
		try {
			createDirectory();
			Path credential = this.dir.resolve(id + CREDENTIAL_EXT);
			Files.writeString(credential, (definition.kubeconfig() != null) ? definition.kubeconfig() : "",
					StandardCharsets.UTF_8);
			restrict(credential, "rw-------");
			Properties meta = new Properties();
			meta.setProperty(KEY_NAME, (definition.name() != null) ? definition.name() : id);
			meta.setProperty(KEY_CONTEXT, (definition.context() != null) ? definition.context() : "");
			Path metaFile = this.dir.resolve(id + META_EXT);
			try (Writer writer = Files.newBufferedWriter(metaFile, StandardCharsets.UTF_8)) {
				meta.store(writer, "kweblens cluster " + id);
			}
			restrict(metaFile, "rw-------");
		}
		catch (IOException ex) {
			throw new UncheckedIOException("Could not persist cluster '" + id + "'", ex);
		}
	}

	@Override
	public void delete(String id) {
		String safe = validated(id);
		try {
			Files.deleteIfExists(this.dir.resolve(safe + META_EXT));
			Files.deleteIfExists(this.dir.resolve(safe + CREDENTIAL_EXT));
		}
		catch (IOException ex) {
			throw new UncheckedIOException("Could not delete cluster '" + safe + "'", ex);
		}
	}

	@Override
	public String describe() {
		return "data directory " + this.dir;
	}

	private Optional<ClusterDefinition> readOne(String id) {
		try {
			Properties meta = new Properties();
			try (var reader = Files.newBufferedReader(this.dir.resolve(id + META_EXT), StandardCharsets.UTF_8)) {
				meta.load(reader);
			}
			Path credential = this.dir.resolve(id + CREDENTIAL_EXT);
			String kubeconfig = Files.isRegularFile(credential) ? Files.readString(credential, StandardCharsets.UTF_8)
					: null;
			String context = meta.getProperty(KEY_CONTEXT, "");
			return Optional.of(new ClusterDefinition(id, meta.getProperty(KEY_NAME, id),
					context.isBlank() ? null : context, kubeconfig));
		}
		catch (IOException | RuntimeException ex) {
			// One unreadable entry must not take the whole store down: the other clusters
			// are still usable, and the operator needs kweblens to start so they can fix
			// it.
			log.warn("Skipping unreadable persisted cluster '{}': {}", id, ex.getMessage());
			return Optional.empty();
		}
	}

	private void createDirectory() throws IOException {
		Files.createDirectories(this.dir);
		restrict(this.dir, "rwx------");
	}

	/**
	 * Tighten permissions where the filesystem supports it. A non-POSIX filesystem
	 * (Windows, some container volume drivers) is not a reason to refuse to persist, so
	 * this degrades to a warning.
	 */
	private void restrict(Path path, String permissions) {
		try {
			Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(permissions));
		}
		catch (UnsupportedOperationException | IOException ex) {
			log.debug("Could not set {} on {}: {}", permissions, path, ex.getMessage());
		}
	}

	private String validated(String id) {
		if (id == null || !SAFE_ID.matcher(id).matches()) {
			throw new InvalidClusterException("Invalid cluster id: " + id);
		}
		return id;
	}

}
