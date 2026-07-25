package org.alexmond.kweblens.web.api;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import org.alexmond.kweblens.cluster.UnknownClusterException;
import org.alexmond.kweblens.portforward.PortForwardException;

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

}
