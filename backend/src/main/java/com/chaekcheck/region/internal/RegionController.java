package com.chaekcheck.region.internal;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.chaekcheck.region.RegionResolution;

@RestController
@RequestMapping("/api/v1/regions")
class RegionController {

	static final int MAX_RESOLUTION_IDS = 20;

	private static final Pattern ID = Pattern.compile("[1-9][0-9]{0,17}");

	/** The address nginx saw (deploy/nginx: {@code proxy_set_header X-Real-IP $remote_addr}). */
	static final String REAL_IP = "X-Real-IP";

	private final RegionService regionService;

	private final SidoByIp sidoByIp;

	RegionController(RegionService regionService, SidoByIp sidoByIp) {
		this.regionService = regionService;
		this.sidoByIp = sidoByIp;
	}

	/** {@code sido} is a short name of {@link #regions()} ("부산"), or null. */
	record SidoGuess(String sido) {
	}

	/**
	 * The 시도 the visitor's address is in, for the first screen's example card only (user 2026-10-20: "예시 카드의
	 * 시도만 IP로 고르기"); null when not known. The address is not kept or logged, and the answer is not cached.
	 */
	@GetMapping("/guess")
	ResponseEntity<SidoGuess> guess(@RequestHeader(name = REAL_IP, required = false) String realIp) {
		return ResponseEntity.ok()
			.cacheControl(CacheControl.noStore())
			.body(new SidoGuess(sidoByIp.sido(realIp).orElse(null)));
	}

	@GetMapping
	List<RegionService.SidoNode> regions() {
		return regionService.activeTree();
	}

	/** {@code ids} is comma separated. Parsed here so every bad input maps to one validation_error shape. */
	@GetMapping("/resolutions")
	List<RegionResolution> resolutions(@RequestParam(name = "ids", required = false) String ids) {
		return regionService.resolve(parseIds(ids));
	}

	private static Set<Long> parseIds(String raw) {
		if (raw == null || raw.isBlank()) {
			throw invalidIds("ids is required", "required");
		}
		Set<Long> ids = new LinkedHashSet<>();
		for (String part : raw.split(",", -1)) {
			String trimmed = part.strip();
			if (!ID.matcher(trimmed).matches()) {
				throw invalidIds("ids must be comma-separated positive integers", "invalid_format");
			}
			ids.add(Long.parseLong(trimmed));
		}
		if (ids.size() > MAX_RESOLUTION_IDS) {
			throw invalidIds("At most " + MAX_RESOLUTION_IDS + " ids are allowed", "too_many");
		}
		return ids;
	}

	private static ErrorResponseException invalidIds(String message, String fieldCode) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Request validation failed");
		problem.setProperty("code", "validation_error");
		problem.setProperty("errors", List.of(Map.of("field", "ids", "message", message, "code", fieldCode)));
		return new ErrorResponseException(HttpStatus.BAD_REQUEST, problem, null);
	}

}
