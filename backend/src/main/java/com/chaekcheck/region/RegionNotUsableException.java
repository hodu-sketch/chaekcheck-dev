package com.chaekcheck.region;

import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Thrown when region ids that must be active SIGUNGU ids are abolished, unknown or SIDO ids.
 */
public class RegionNotUsableException extends RuntimeException {

	public enum Reason {
		/** The region exists but was abolished. */
		ABOLISHED,
		/** No region has this id. */
		NOT_FOUND,
		/** The id is a SIDO; only SIGUNGU ids can be chosen. */
		NOT_SIGUNGU
	}

	private final Map<Long, Reason> reasons;

	public RegionNotUsableException(Map<Long, Reason> reasons) {
		super("Regions not usable: " + new TreeMap<>(reasons));
		this.reasons = Map.copyOf(reasons);
	}

	/** Reason for each id that is not usable. */
	public Map<Long, Reason> reasons() {
		return reasons;
	}

	public Set<Long> regionIds() {
		return reasons.keySet();
	}

}
