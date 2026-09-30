package com.chaekcheck.availability.internal;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

/**
 * Problem Details used by the availability APIs (ADR-0008), shared by HTTP errors and stream failure events.
 */
public final class Problems {

	private Problems() {
	}

	public static ProblemDetail problem(HttpStatus status, String code, String detail) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
		problem.setTitle(status.getReasonPhrase());
		problem.setProperty("code", code);
		return problem;
	}

	/** 504 upstream_timeout or 502 upstream_error. Never includes the upstream message or body. */
	public static ProblemDetail upstream(UpstreamException ex) {
		return ex.kind() == UpstreamException.Kind.TIMEOUT
				? problem(HttpStatus.GATEWAY_TIMEOUT, "upstream_timeout", "The library did not respond in time")
				: problem(HttpStatus.BAD_GATEWAY, "upstream_error",
						"The library returned an error or an unexpected page");
	}

	/** The same members as the HTTP body, for use inside an event. */
	public static Map<String, Object> asMap(ProblemDetail problem) {
		Map<String, Object> map = new LinkedHashMap<>();
		map.put("title", problem.getTitle());
		map.put("status", problem.getStatus());
		map.put("detail", problem.getDetail());
		if (problem.getProperties() != null) {
			map.putAll(problem.getProperties());
		}
		return map;
	}

}
