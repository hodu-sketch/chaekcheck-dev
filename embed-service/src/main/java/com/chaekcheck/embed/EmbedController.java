package com.chaekcheck.embed;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /api/v1/embed} with {@code {"text": "..."}} and {@code X-Embed-Token} answers {@code {"vector": [...]}}
 * (1,024 numbers); a wrong or missing token 401, no or too long text 400, the model not loaded 503. {@code GET
 * /api/v1/health} says whether the model is loaded. The text is never logged. Reached only from the main service
 * inside the VPC (the security group); the token is the second lock.
 */
@RestController
class EmbedController {

	static final String TOKEN_HEADER = "X-Embed-Token";

	private final Embedder embedder;

	private final EmbedProperties properties;

	EmbedController(Embedder embedder, EmbedProperties properties) {
		this.embedder = embedder;
		this.properties = properties;
	}

	record EmbedRequest(String text) {
	}

	record EmbedResponse(float[] vector) {
	}

	@PostMapping("/api/v1/embed")
	ResponseEntity<?> embed(@RequestHeader(name = TOKEN_HEADER, required = false) String token,
			@RequestBody(required = false) EmbedRequest request) {
		if (!allowed(token)) {
			return problem(HttpStatus.UNAUTHORIZED, "unauthorized", "a valid " + TOKEN_HEADER + " is needed");
		}
		if (request == null || request.text() == null || request.text().isBlank()
				|| request.text().length() > properties.maxChars()) {
			return problem(HttpStatus.BAD_REQUEST, "validation_error",
					"text is needed, at most " + properties.maxChars() + " characters");
		}
		if (!embedder.ready()) {
			return problem(HttpStatus.SERVICE_UNAVAILABLE, "model_not_ready", "the model is not loaded");
		}
		return ResponseEntity.ok(new EmbedResponse(embedder.embed(request.text())));
	}

	@GetMapping("/api/v1/health")
	ResponseEntity<Map<String, Object>> health() {
		boolean ready = embedder.ready();
		return ResponseEntity.status(ready ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("ready", ready));
	}

	/** Constant-time comparison; an empty configured token refuses everyone. */
	private boolean allowed(String token) {
		if (properties.token().isEmpty() || token == null) {
			return false;
		}
		return MessageDigest.isEqual(properties.token().getBytes(StandardCharsets.UTF_8),
				token.getBytes(StandardCharsets.UTF_8));
	}

	private static ResponseEntity<ProblemDetail> problem(HttpStatus status, String code, String detail) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
		problem.setProperty("code", code);
		return ResponseEntity.status(status).body(problem);
	}

}
