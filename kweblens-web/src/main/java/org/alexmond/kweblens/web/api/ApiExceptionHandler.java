package org.alexmond.kweblens.web.api;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import org.alexmond.kweblens.cluster.UnknownClusterException;
import org.alexmond.kweblens.portforward.PortForwardException;
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

	@ExceptionHandler(PortForwardException.class)
	public ProblemDetail portForward(PortForwardException ex) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
		problem.setTitle("Port-forward failed");
		problem.setProperties(Map.of("code", "port-forward-failed"));
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
