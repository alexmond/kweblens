package org.alexmond.kweblens.web.api;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import org.alexmond.kweblens.cluster.ClusterConflictException;
import org.alexmond.kweblens.cluster.InvalidClusterException;
import org.alexmond.kweblens.cluster.UnknownClusterException;
import org.alexmond.kweblens.portforward.PortForwardException;
import org.alexmond.kweblens.web.ai.AiUnavailableException;
import org.alexmond.kweblens.web.helm.HelmException;

/**
 * Maps domain errors from the access layer onto HTTP responses for the JSON API.
 */
@RestControllerAdvice(basePackages = "org.alexmond.kweblens.web.api")
public class ApiExceptionHandler {

	@ExceptionHandler(UnknownClusterException.class)
	public ProblemDetail unknownCluster(UnknownClusterException ex) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
		problem.setTitle("Unknown cluster");
		problem.setProperties(Map.of("code", "unknown-cluster"));
		return problem;
	}

	/**
	 * A cluster definition that cannot be used. Rejected before anything is registered or
	 * persisted, so the registry is unchanged when this is returned.
	 */
	@ExceptionHandler(InvalidClusterException.class)
	public ProblemDetail invalidCluster(InvalidClusterException ex) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
		problem.setTitle("Invalid cluster");
		problem.setProperties(Map.of("code", "invalid-cluster"));
		return problem;
	}

	/**
	 * A well-formed request that conflicts with current state — a duplicate id, or
	 * editing a cluster the deployment declared rather than one added at runtime.
	 */
	@ExceptionHandler(ClusterConflictException.class)
	public ProblemDetail clusterConflict(ClusterConflictException ex) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
		problem.setTitle("Cluster conflict");
		problem.setProperties(Map.of("code", "cluster-conflict"));
		return problem;
	}

	@ExceptionHandler(PortForwardException.class)
	public ProblemDetail portForward(PortForwardException ex) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
		problem.setTitle("Port-forward failed");
		problem.setProperties(Map.of("code", "port-forward-failed"));
		return problem;
	}

	/**
	 * An analysis was requested from an instance with no LLM configured. 409 rather than
	 * 500: nothing broke and nothing was spent — the server is simply not set up to do
	 * it, which the caller can tell in advance from {@code aiAvailable} on the diagnosis
	 * read.
	 */
	@ExceptionHandler(AiUnavailableException.class)
	public ProblemDetail aiUnavailable(AiUnavailableException ex) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
		problem.setTitle("AI analysis unavailable");
		problem.setProperties(Map.of("code", "ai-unavailable"));
		return problem;
	}

	@ExceptionHandler(HelmException.class)
	public ProblemDetail helm(HelmException ex) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
		problem.setTitle("Helm action failed");
		problem.setProperties(Map.of("code", "helm-failed"));
		return problem;
	}

	/**
	 * Bad input from the caller — an unresolvable log target, a workload with no
	 * selector, a pod that does not exist. Without this mapping these surfaced as 500s,
	 * which told the client "kweblens broke" when the truth was "that request cannot be
	 * satisfied", and hid the (deliberately explanatory) message behind a generic error
	 * page.
	 */
	@ExceptionHandler(IllegalArgumentException.class)
	public ProblemDetail badRequest(IllegalArgumentException ex) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
		problem.setTitle("Invalid request");
		problem.setProperties(Map.of("code", "invalid-request"));
		return problem;
	}

}
