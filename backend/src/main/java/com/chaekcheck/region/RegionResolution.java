package com.chaekcheck.region;

import java.util.List;

/**
 * Resolution of one stored region id.
 * @param label display label such as "서울 강서구"; null when {@code status} is UNKNOWN
 * @param replacements active regions that replace this one; empty unless REPLACED or SPLIT
 */
public record RegionResolution(long id, Status status, String label, List<Replacement> replacements) {

	public enum Status {
		ACTIVE, REPLACED, SPLIT, UNKNOWN
	}

	public record Replacement(long id, String label) {
	}

	public RegionResolution {
		replacements = List.copyOf(replacements);
	}

}
