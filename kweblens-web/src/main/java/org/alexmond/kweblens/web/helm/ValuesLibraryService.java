package org.alexmond.kweblens.web.helm;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * A small persisted library of reusable Helm values files. Each entry is a
 * {@code <name>.yaml} file under {@code kweblens.helm.values-path} (the persisted Helm
 * home), so saved values survive restarts on a mounted volume and can be reused across
 * installs/upgrades.
 */
@Slf4j
@Service
public class ValuesLibraryService {

	/** Names are strictly validated so a name can never escape the library directory. */
	private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");

	private static final String EXT = ".yaml";

	private final Path dir;

	public ValuesLibraryService(HelmProperties properties) {
		String configured = properties.getValuesPath();
		this.dir = Path.of(StringUtils.hasText(configured) ? configured
				: System.getProperty("java.io.tmpdir") + "/kweblens-helm/values");
	}

	/** Saved values names (without the {@code .yaml} suffix), sorted. */
	public List<String> list() {
		if (!Files.isDirectory(dir)) {
			return List.of();
		}
		try (Stream<Path> files = Files.list(dir)) {
			return files.map((p) -> p.getFileName().toString())
				.filter((n) -> n.endsWith(EXT))
				.map((n) -> n.substring(0, n.length() - EXT.length()))
				.sorted()
				.toList();
		}
		catch (IOException ex) {
			throw new UncheckedIOException("Could not list the values library", ex);
		}
	}

	/** The YAML of a saved values file, or empty when it does not exist. */
	public String get(String name) {
		Path file = resolve(name);
		if (!Files.isRegularFile(file)) {
			return null;
		}
		try {
			return Files.readString(file, StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			throw new UncheckedIOException("Could not read values '" + name + "'", ex);
		}
	}

	/** Create or overwrite a saved values file. */
	public void save(String name, String yaml) {
		Path file = resolve(name);
		try {
			Files.createDirectories(dir);
			Files.writeString(file, (yaml != null) ? yaml : "", StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			throw new UncheckedIOException("Could not save values '" + name + "'", ex);
		}
	}

	/** Delete a saved values file (no-op if absent). */
	public void delete(String name) {
		try {
			Files.deleteIfExists(resolve(name));
		}
		catch (IOException ex) {
			throw new UncheckedIOException("Could not delete values '" + name + "'", ex);
		}
	}

	/**
	 * Resolve a validated name to a path inside {@link #dir}; reject traversal attempts.
	 */
	private Path resolve(String name) {
		if (name == null || !SAFE_NAME.matcher(name).matches()) {
			throw new HelmException(
					"Invalid values name (allowed: letters, digits, '.', '_', '-', up to 64 chars): " + name);
		}
		return dir.resolve(name + EXT);
	}

}
