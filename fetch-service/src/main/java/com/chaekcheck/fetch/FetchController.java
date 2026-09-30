package com.chaekcheck.fetch;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.Semaphore;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /v1/fetches} (ADR-0015). 200 with a {@link FetchResult} whenever this service is healthy; 401 without
 * the right token, 400 for a URL it must not fetch, 503 when busy or shutting down (the main service then tries its
 * next fetch server).
 */
@RestController
class FetchController {

	private static final Logger log = LoggerFactory.getLogger(FetchController.class);

	private final FetchProperties properties;

	private final UrlGuard urlGuard;

	private final LibraryClient libraryClient;

	private final Semaphore slots;

	private volatile boolean shuttingDown;

	FetchController(FetchProperties properties, UrlGuard urlGuard, LibraryClient libraryClient) {
		this.properties = properties;
		this.urlGuard = urlGuard;
		this.libraryClient = libraryClient;
		this.slots = new Semaphore(properties.maxConcurrent());
	}

	@PostMapping("/v1/fetches")
	FetchResult fetch(@RequestHeader HttpHeaders headers, @RequestBody FetchRequest request) {
		if (!tokenMatches(headers)) {
			throw new UnauthorizedException();
		}
		if (shuttingDown || !slots.tryAcquire()) {
			throw new UnavailableException();
		}
		try {
			return libraryClient.get(urlGuard.check(request.url()), request.requestId());
		}
		finally {
			slots.release();
		}
	}

	@EventListener(ContextClosedEvent.class)
	void closing() {
		shuttingDown = true;
	}

	private boolean tokenMatches(HttpHeaders headers) {
		String expected = properties.token();
		if (expected == null || expected.isEmpty()) {
			return false;
		}
		String sent = headers.getFirst(properties.tokenHeader());
		if (sent == null) {
			return false;
		}
		return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), sent.getBytes(StandardCharsets.UTF_8));
	}

	@ExceptionHandler
	ProblemDetail unauthorized(UnauthorizedException ex) {
		return problem(HttpStatus.UNAUTHORIZED, "Missing or wrong token", "unauthorized");
	}

	@ExceptionHandler
	ProblemDetail badUrl(UrlGuard.BadUrlException ex) {
		log.info("rejected url: {}", ex.getMessage());
		return problem(HttpStatus.BAD_REQUEST, "This URL cannot be fetched", "invalid_url");
	}

	@ExceptionHandler
	ProblemDetail unavailable(UnavailableException ex) {
		return problem(HttpStatus.SERVICE_UNAVAILABLE, "Busy or shutting down", "unavailable");
	}

	private static ProblemDetail problem(HttpStatus status, String detail, String code) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
		problem.setProperty("code", code);
		return problem;
	}

	record FetchRequest(String url, String requestId) {
	}

	static class UnauthorizedException extends RuntimeException {

	}

	static class UnavailableException extends RuntimeException {

	}

}
