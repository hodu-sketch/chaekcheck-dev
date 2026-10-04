package com.chaekcheck.mylibrary.internal.web;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.chaekcheck.auth.AccountId;
import com.chaekcheck.auth.ConditionalOnAuthEnabled;
import com.chaekcheck.mylibrary.internal.MyLibraryService;
import com.chaekcheck.mylibrary.internal.MyLibraryService.SavedRegions;

import tools.jackson.databind.JsonNode;

/**
 * Saved regions and added libraries of the signed-in account (design-delta API, ADR-0049). Under
 * {@code /api/v1/me/**}: the access cookie, the per-request account check and, for changes, the CSRF header are
 * already applied (ADR-0044). Regions are replaced whole, libraries are added and removed one at a time. 404 when
 * login is off (no bean).
 */
@RestController
@ConditionalOnAuthEnabled
class MyLibraryController {

	private static final Pattern LIBRARY_ID = Pattern.compile("[1-9][0-9]{0,17}");

	private final MyLibraryService service;

	MyLibraryController(MyLibraryService service) {
		this.service = service;
	}

	@GetMapping("/api/v1/me/regions")
	SavedRegions regions(AccountId account) {
		return service.regions(account);
	}

	/**
	 * Both fields required (empty arrays allowed), positive integers, duplicates merged keeping the first, at most 20
	 * each, order kept: 400 {@code validation_error}. Then 401 when the account is gone, 422 {@code invalid_region}.
	 * Read as a tree so a wrong value gets its field in {@code errors[]} instead of a bare 400.
	 */
	@PutMapping("/api/v1/me/regions")
	SavedRegions saveRegions(AccountId account, @RequestBody JsonNode body) {
		List<FieldError> errors = new ArrayList<>();
		List<Long> residence = regionIds(body, "residenceRegionIds", errors);
		List<Long> work = regionIds(body, "workRegionIds", errors);
		if (!errors.isEmpty()) {
			throw new InvalidBodyException(errors);
		}
		return service.saveRegions(account, residence, work);
	}

	@GetMapping("/api/v1/me/libraries")
	Libraries libraries(AccountId account) {
		return new Libraries(service.libraryIds(account), MyLibraryService.MAX_LIBRARIES);
	}

	/** 204, also when it is already there; 404 {@code library_not_found}, 409 {@code library_limit_reached}. */
	@PutMapping("/api/v1/me/libraries/{libraryId}")
	ResponseEntity<Void> addLibrary(AccountId account, @PathVariable String libraryId) {
		service.addLibrary(account, parseLibraryId(libraryId));
		return ResponseEntity.noContent().build();
	}

	/** 204, also when it was not there. A path that is not a library number is 404 {@code library_not_found}. */
	@DeleteMapping("/api/v1/me/libraries/{libraryId}")
	ResponseEntity<Void> removeLibrary(AccountId account, @PathVariable String libraryId) {
		service.removeLibrary(account, parseLibraryId(libraryId));
		return ResponseEntity.noContent().build();
	}

	private static long parseLibraryId(String raw) {
		if (!LIBRARY_ID.matcher(raw).matches()) {
			throw new LibraryPathException();
		}
		return Long.parseLong(raw);
	}

	private static List<Long> regionIds(JsonNode body, String field, List<FieldError> errors) {
		JsonNode value = body == null ? null : body.get(field);
		if (value == null || value.isNull()) {
			errors.add(new FieldError(field, "required", field + " is required"));
			return List.of();
		}
		if (!value.isArray()) {
			errors.add(new FieldError(field, "invalid_format", field + " must be an array of positive integers"));
			return List.of();
		}
		Set<Long> ids = new LinkedHashSet<>();
		for (JsonNode element : value) {
			if (!element.isIntegralNumber() || !element.canConvertToLong() || element.longValue() <= 0) {
				errors.add(new FieldError(field, "invalid_format", field + " must be an array of positive integers"));
				return List.of();
			}
			ids.add(element.longValue());
			if (ids.size() > MyLibraryService.MAX_REGIONS) {
				errors.add(new FieldError(field, "too_many",
						"At most " + MyLibraryService.MAX_REGIONS + " ids are allowed"));
				return List.of();
			}
		}
		return List.copyOf(ids);
	}

	record Libraries(List<Long> libraryIds, int max) {
	}

	record FieldError(String field, String code, String message) {
	}

	static final class InvalidBodyException extends RuntimeException {

		final List<FieldError> errors;

		InvalidBodyException(List<FieldError> errors) {
			super(errors.getFirst().message());
			this.errors = List.copyOf(errors);
		}

	}

	static final class LibraryPathException extends RuntimeException {

		LibraryPathException() {
			super("Library id must be a positive integer");
		}

	}

}
