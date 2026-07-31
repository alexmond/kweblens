package org.alexmond.kweblens.web.files;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import lombok.Data;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings for the pod file browser ({@code kweblens.files.*}).
 *
 * <p>
 * <strong>Off by default, deliberately.</strong> Browsing a container's filesystem is
 * arbitrary read (and, for writes, arbitrary modification) inside every pod kweblens can
 * reach — including projected Secret volumes and the service-account token under
 * {@code /var/run/secrets}. kweblens runs as a single shared identity and is not
 * RBAC-aware (see {@code docs/design/adr-001-identity-model.md}), so enabling this widens
 * what one compromised session yields. ADR-001's sign-off requires it to stay opt-in.
 */
@Data
@ConfigurationProperties(prefix = "kweblens.files")
public class FilesProperties {

	/**
	 * Enable the pod file browser API. Off by default: it can read mounted Secrets and
	 * service-account tokens off a container's disk, which no other kweblens surface
	 * exposes.
	 */
	private boolean enabled;

	/**
	 * Allow modifying and deleting files inside containers. Set {@code false} for a
	 * browse-and-download-only deployment; the whole feature is still off unless
	 * {@code enabled} is set.
	 */
	private boolean writable = true;

	/**
	 * Largest file (bytes) that may be viewed or downloaded; larger files are refused.
	 */
	private long maxReadBytes = 1_048_576;

	/** Largest payload (bytes) that may be written into a container. */
	private long maxWriteBytes = 1_048_576;

	/** Largest number of directory entries returned in one listing. */
	private int maxEntries = 1000;

	/** How long a single in-container command may take before it is abandoned. */
	private Duration commandTimeout = Duration.ofSeconds(20);

	/**
	 * Absolute path prefixes the browser is confined to. Empty (the default) means the
	 * whole container filesystem. When set, the requested path is normalised, resolved
	 * inside the container ({@code readlink -f}) and both must sit under a listed root,
	 * so a symlink cannot be used to step outside; a path that cannot be resolved is
	 * refused rather than allowed.
	 */
	private List<String> allowedRoots = new ArrayList<>();

}
