package org.alexmond.kweblens.web.files;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import lombok.RequiredArgsConstructor;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The pod file browser API.
 *
 * <p>
 * Disabled by default — see {@link FilesProperties}. Unlike the rest of the read API,
 * <strong>the GETs here are not public in open-mode</strong>: {@code SecurityConfig}
 * matches this whole path family as {@code authenticated()}, because a public file read
 * would turn "kweblens is reachable" into "every Secret mounted in every pod is
 * downloadable without logging in".
 */
@RestController
@RequestMapping("/api/v1/clusters/{clusterId}/pods/{namespace}/{pod}/files")
@RequiredArgsConstructor
public class PodFilesApiController {

	private final PodFileService files;

	@GetMapping
	public PodDirectoryListing list(@PathVariable String clusterId, @PathVariable String namespace,
			@PathVariable String pod, @RequestParam(required = false) String container,
			@RequestParam(defaultValue = "/") String path) {
		return files.list(clusterId, namespace, pod, container, path);
	}

	@GetMapping("/content")
	public PodFileContent read(@PathVariable String clusterId, @PathVariable String namespace, @PathVariable String pod,
			@RequestParam(required = false) String container, @RequestParam String path) {
		return files.read(clusterId, namespace, pod, container, path);
	}

	@GetMapping("/download")
	public ResponseEntity<byte[]> download(@PathVariable String clusterId, @PathVariable String namespace,
			@PathVariable String pod, @RequestParam(required = false) String container, @RequestParam String path) {
		byte[] content = files.download(clusterId, namespace, pod, container, path);
		ContentDisposition disposition = ContentDisposition.attachment()
			.filename(downloadName(path), StandardCharsets.UTF_8)
			.build();
		return ResponseEntity.ok()
			.header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
			.contentType(MediaType.APPLICATION_OCTET_STREAM)
			.body(content);
	}

	@PutMapping("/content")
	public Map<String, String> write(@PathVariable String clusterId, @PathVariable String namespace,
			@PathVariable String pod, @RequestParam(required = false) String container, @RequestParam String path,
			@RequestBody PodFileWrite body) {
		files.write(clusterId, namespace, pod, container, path, body);
		return Map.of("result", "wrote " + path);
	}

	@DeleteMapping
	public Map<String, String> delete(@PathVariable String clusterId, @PathVariable String namespace,
			@PathVariable String pod, @RequestParam(required = false) String container, @RequestParam String path) {
		files.delete(clusterId, namespace, pod, container, path);
		return Map.of("result", "deleted " + path);
	}

	/**
	 * A filename safe to put in a response header. A container filename may legally
	 * contain newlines and quotes; {@code ContentDisposition} percent-encodes the UTF-8
	 * form, but control characters are stripped first so nothing can be smuggled into the
	 * header even if that encoding is ever relaxed.
	 */
	private String downloadName(String path) {
		StringBuilder safe = new StringBuilder();
		for (char ch : PodFilePath.fileName(PodFilePath.normalize(path)).toCharArray()) {
			safe.append((Character.isISOControl(ch) || ch == '"' || ch == '\\') ? '_' : ch);
		}
		String name = safe.toString().trim();
		return (name.isEmpty()) ? "download" : name;
	}

}
