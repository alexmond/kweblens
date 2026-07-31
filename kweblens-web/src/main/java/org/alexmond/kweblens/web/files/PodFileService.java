package org.alexmond.kweblens.web.files;

import java.util.List;

import lombok.RequiredArgsConstructor;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import org.alexmond.kweblens.exec.ExecFailedException;
import org.alexmond.kweblens.exec.ExecResult;
import org.alexmond.kweblens.exec.ExecService;
import org.alexmond.kweblens.web.security.AuditService;

/**
 * Browses, reads, writes and deletes files inside a running container.
 *
 * <p>
 * Every operation is one short-lived {@code sh} command run through
 * {@link ExecService#run} — the same Kubernetes exec API the in-browser terminal rides,
 * not a second way of getting into a container. The scripts and the reasoning behind them
 * (no {@code ls -la} parsing, NUL-delimited output, never interpolating a path into a
 * shell string) are in {@link PodFileScripts}.
 *
 * <p>
 * <strong>This surface is dangerous and behaves accordingly.</strong> It can read a
 * projected Secret volume or a service-account token straight off disk, which no other
 * kweblens view exposes, so:
 *
 * <ul>
 * <li>it is <strong>off unless {@code kweblens.files.enabled}</strong> is set — ADR-001's
 * sign-off requires that while kweblens runs as one shared identity;</li>
 * <li><strong>reads are treated as privileged</strong>: the whole endpoint family is
 * authenticated even in open-mode, where GETs are otherwise public (see
 * {@code SecurityConfig});</li>
 * <li>every content read, download, write and delete is recorded by {@link AuditService}
 * with the path. Directory listings are not — they are the browsing noise around the
 * events that actually move data, and drowning the bounded audit ring in them would hide
 * the reads that matter.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class PodFileService {

	private static final long LISTING_BYTES_PER_ENTRY = 4096;

	private final ExecService exec;

	private final FilesProperties properties;

	private final AuditService audit;

	public PodDirectoryListing list(String clusterId, String namespace, String pod, String container, String rawPath) {
		requireEnabled();
		String path = prepare(rawPath);
		List<String> command = PodFileScripts.command(PodFileScripts.LIST, path,
				Integer.toString(properties.getMaxEntries()));
		long cap = LISTING_BYTES_PER_ENTRY * properties.getMaxEntries();
		ExecResult result = run(clusterId, namespace, pod, container, command, null, cap);
		if (!result.succeeded()) {
			throw PodFileFailures.translate(result, path);
		}
		PodDirectoryListing listing = PodFileParser.listing(path, container, result.stdout());
		requireResolvedWithinRoots(listing.resolvedPath(), path);
		return listing;
	}

	public PodFileContent read(String clusterId, String namespace, String pod, String container, String rawPath) {
		requireEnabled();
		String path = prepare(rawPath);
		PodFileStat stat = readableFile(clusterId, namespace, pod, container, path);
		byte[] content = readBytes(clusterId, namespace, pod, container, path);
		audit.record(clusterId, "file-read", target(namespace, pod, container, path));
		long size = (stat.size() != null) ? stat.size() : content.length;
		return PodFileCodec.describe(path, container, size, content, content.length < size);
	}

	/** The raw bytes of a file, for a download. */
	public byte[] download(String clusterId, String namespace, String pod, String container, String rawPath) {
		requireEnabled();
		String path = prepare(rawPath);
		readableFile(clusterId, namespace, pod, container, path);
		byte[] content = readBytes(clusterId, namespace, pod, container, path);
		audit.record(clusterId, "file-download", target(namespace, pod, container, path));
		return content;
	}

	public void write(String clusterId, String namespace, String pod, String container, String rawPath,
			PodFileWrite body) {
		requireWritable();
		String path = prepare(rawPath);
		byte[] payload = PodFileCodec.decode(body);
		if (payload.length > properties.getMaxWriteBytes()) {
			throw new PodFileException(HttpStatus.PAYLOAD_TOO_LARGE, "file-too-large", "payload is " + payload.length
					+ " bytes; kweblens.files.max-write-bytes allows " + properties.getMaxWriteBytes());
		}
		PodFileStat stat = stat(clusterId, namespace, pod, container, path);
		if (stat.isDirectory()) {
			throw new PodFileException(HttpStatus.BAD_REQUEST, "is-a-directory", path + " is a directory");
		}
		requireResolvedWithinRoots(stat.realPath(), path);
		List<String> command = PodFileScripts.command(PodFileScripts.WRITE, path, Integer.toString(payload.length));
		ExecResult result = run(clusterId, namespace, pod, container, command, payload, 8192);
		if (!result.succeeded()) {
			throw PodFileFailures.translate(result, path);
		}
		audit.record(clusterId, "file-write=" + payload.length + "B", target(namespace, pod, container, path));
	}

	public void delete(String clusterId, String namespace, String pod, String container, String rawPath) {
		requireWritable();
		String path = prepare(rawPath);
		PodFileStat stat = stat(clusterId, namespace, pod, container, path);
		if (!stat.exists()) {
			throw new PodFileException(HttpStatus.NOT_FOUND, "file-not-found",
					"no such file or directory in the container: " + path);
		}
		requireResolvedWithinRoots(stat.realPath(), path);
		List<String> command = PodFileScripts.command(PodFileScripts.DELETE, path);
		ExecResult result = run(clusterId, namespace, pod, container, command, null, 8192);
		if (!result.succeeded()) {
			throw PodFileFailures.translate(result, path);
		}
		audit.record(clusterId, "file-delete", target(namespace, pod, container, path));
	}

	/** Stat a path and insist it is a regular file small enough to transfer. */
	private PodFileStat readableFile(String clusterId, String namespace, String pod, String container, String path) {
		PodFileStat stat = stat(clusterId, namespace, pod, container, path);
		if (!stat.exists()) {
			throw new PodFileException(HttpStatus.NOT_FOUND, "file-not-found",
					"no such file or directory in the container: " + path);
		}
		if (stat.isDirectory()) {
			throw new PodFileException(HttpStatus.BAD_REQUEST, "is-a-directory",
					path + " is a directory — list it instead of reading it");
		}
		requireResolvedWithinRoots(stat.realPath(), path);
		if (stat.size() != null && stat.size() > properties.getMaxReadBytes()) {
			throw new PodFileException(HttpStatus.PAYLOAD_TOO_LARGE, "file-too-large",
					path + " is " + stat.size() + " bytes; kweblens.files.max-read-bytes allows "
							+ properties.getMaxReadBytes()
							+ ". kweblens refuses rather than handing back a silently truncated copy.");
		}
		return stat;
	}

	private byte[] readBytes(String clusterId, String namespace, String pod, String container, String path) {
		List<String> command = PodFileScripts.command(PodFileScripts.READ, path,
				Long.toString(properties.getMaxReadBytes()));
		ExecResult result = run(clusterId, namespace, pod, container, command, null, properties.getMaxReadBytes());
		if (!result.succeeded()) {
			throw PodFileFailures.translate(result, path);
		}
		return result.stdout();
	}

	private PodFileStat stat(String clusterId, String namespace, String pod, String container, String path) {
		List<String> command = PodFileScripts.command(PodFileScripts.STAT, path);
		ExecResult result = run(clusterId, namespace, pod, container, command, null, 8192);
		if (!result.succeeded()) {
			throw PodFileFailures.translate(result, path);
		}
		return PodFileParser.stat(result.stdout());
	}

	private ExecResult run(String clusterId, String namespace, String pod, String container, List<String> command,
			byte[] stdin, long maxOutputBytes) {
		try {
			return exec.run(clusterId, namespace, pod, container, command, stdin, maxOutputBytes,
					properties.getCommandTimeout());
		}
		catch (ExecFailedException ex) {
			throw PodFileFailures.translate(ex, namespace, pod);
		}
	}

	private void requireEnabled() {
		if (!properties.isEnabled()) {
			throw new PodFileException(HttpStatus.FORBIDDEN, "files-disabled",
					"the pod file browser is disabled. It is off by default because it can read mounted"
							+ " Secrets and service-account tokens off a container's disk; set"
							+ " kweblens.files.enabled=true to turn it on deliberately.");
		}
	}

	private void requireWritable() {
		requireEnabled();
		if (!properties.isWritable()) {
			throw new PodFileException(HttpStatus.FORBIDDEN, "files-read-only",
					"the pod file browser is read-only (kweblens.files.writable=false)");
		}
	}

	private String prepare(String rawPath) {
		String path = PodFilePath.normalize(rawPath);
		if (!PodFilePath.isWithinRoots(path, properties.getAllowedRoots())) {
			throw outsideRoots(path);
		}
		return path;
	}

	/**
	 * Re-check the path the container actually resolved. The requested path was already
	 * normalised, but a symlink inside the container can point anywhere, so when roots
	 * are configured the resolved path has to satisfy them too — and a path the container
	 * could not resolve is refused rather than allowed, so the check fails closed.
	 */
	private void requireResolvedWithinRoots(String resolved, String path) {
		if (properties.getAllowedRoots().isEmpty()) {
			return;
		}
		if (!StringUtils.hasText(resolved)) {
			throw new PodFileException(HttpStatus.FORBIDDEN, "unresolvable-path",
					"cannot confirm where " + path + " really points inside the container"
							+ " (no usable readlink), so it is refused while kweblens.files.allowed-roots is set");
		}
		if (!PodFilePath.isWithinRoots(PodFilePath.normalize(resolved), properties.getAllowedRoots())) {
			throw outsideRoots(resolved);
		}
	}

	private PodFileException outsideRoots(String path) {
		return new PodFileException(HttpStatus.FORBIDDEN, "path-outside-roots",
				path + " is outside kweblens.files.allowed-roots " + properties.getAllowedRoots());
	}

	private String target(String namespace, String pod, String container, String path) {
		String name = (StringUtils.hasText(container)) ? container : "default";
		return AuditService.ref("Pod", namespace, pod) + "[" + name + "]:" + path;
	}

}
