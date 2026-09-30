package com.chaekcheck.library;

import java.util.List;

/**
 * Thrown by {@link LibraryCatalog#planSearch} when a sent region id cannot be used for matching.
 * Carries which request field held each bad id, so callers do not depend on the region module.
 */
public class InvalidRegionException extends RuntimeException {

	public enum Reason {
		ABOLISHED, NOT_FOUND, NOT_SIGUNGU
	}

	/** @param field {@code residenceRegionIds} or {@code workRegionIds} */
	public record InvalidRegion(String field, long regionId, Reason reason) {
	}

	private final List<InvalidRegion> invalidRegions;

	public InvalidRegionException(List<InvalidRegion> invalidRegions) {
		super("Invalid regions: " + invalidRegions);
		this.invalidRegions = List.copyOf(invalidRegions);
	}

	public List<InvalidRegion> invalidRegions() {
		return invalidRegions;
	}

}
