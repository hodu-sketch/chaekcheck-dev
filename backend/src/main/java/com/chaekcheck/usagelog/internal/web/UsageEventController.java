package com.chaekcheck.usagelog.internal.web;

import java.io.IOException;
import java.io.InputStream;

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import tools.jackson.databind.json.JsonMapper;

import com.chaekcheck.usagelog.UsageRequest;
import com.chaekcheck.usagelog.internal.DefaultUsageLog;

/**
 * Usage reports from the web (ADR-0097, cycle 15 step 6): what the server does not see, sent with {@code fetch}
 * keepalive. Always 204 without a body, whatever was sent and whether logging is on; with login on, Spring Security
 * answers 403 first when the CSRF token is missing (ADR-0102). No login is needed. Robots are not recorded.
 */
@RestController
class UsageEventController {

	private static final Logger log = LoggerFactory.getLogger(UsageEventController.class);

	/** Largest body read [안: 8KB]; a larger one is rejected as {@code malformed}. */
	static final int MAX_BODY_BYTES = 8 * 1024;

	private final DefaultUsageLog usageLog;

	private final UsageEventReader reader;

	UsageEventController(DefaultUsageLog usageLog, JsonMapper json) {
		this.usageLog = usageLog;
		this.reader = new UsageEventReader(json);
	}

	@PostMapping("/api/v1/usage-events")
	ResponseEntity<Void> report(HttpServletRequest request) {
		try {
			UsageRequest usage = usageLog.capture(request);
			if (usage.recorded()) {
				byte[] body = readBody(request);
				boolean tooLarge = body.length > MAX_BODY_BYTES;
				usageLog.offerReport(reader.read(body, tooLarge, usage));
			}
		}
		catch (IOException | RuntimeException ex) {
			// a report never gets an error back (R2-7)
			log.debug("usage report not read: {}", ex.toString());
		}
		return ResponseEntity.noContent().build();
	}

	/** At most one byte more than the limit, so a large body is never read into memory. */
	private static byte[] readBody(HttpServletRequest request) throws IOException {
		try (InputStream in = request.getInputStream()) {
			return in.readNBytes(MAX_BODY_BYTES + 1);
		}
	}

}
