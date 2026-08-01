package org.alexmond.kweblens.web.files;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import org.alexmond.kweblens.cluster.UnknownClusterException;
import org.alexmond.kweblens.exec.ExecFailedException;

/**
 * Maps file-browser errors onto {@code ProblemDetail} responses. Scoped to this package
 * because {@code web/api}'s advice is scoped to its own — a shared advice would have to
 * know about every slice.
 *
 * <p>
 * Each problem carries a stable {@code code} so the UI can react ("this container has no
 * shell", "the feature is switched off") rather than showing a raw message, and so a
 * refusal is never mistaken for an empty directory.
 */
@RestControllerAdvice(basePackages = "org.alexmond.kweblens.web.files")
public class PodFilesExceptionHandler {

	@ExceptionHandler(PodFileException.class)
	public ProblemDetail podFile(PodFileException ex) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(ex.getStatus(), ex.getMessage());
		problem.setTitle("Pod file browser");
		problem.setProperties(Map.of("code", ex.getCode()));
		return problem;
	}

	/**
	 * The command never started — the pod vanished, the API server refused the exec, or
	 * the image has no shell. Reported as a gateway failure because the fault is
	 * downstream of kweblens, not in the request.
	 */
	@ExceptionHandler(ExecFailedException.class)
	public ProblemDetail execFailed(ExecFailedException ex) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, ex.getMessage());
		problem.setTitle("Container command failed");
		problem.setProperties(Map.of("code", "container-command-failed"));
		return problem;
	}

	@ExceptionHandler(UnknownClusterException.class)
	public ProblemDetail unknownCluster(UnknownClusterException ex) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
		problem.setTitle("Unknown cluster");
		problem.setProperties(Map.of("code", "unknown-cluster"));
		return problem;
	}

}
