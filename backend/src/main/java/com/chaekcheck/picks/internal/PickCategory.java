package com.chaekcheck.picks.internal;

import java.util.Optional;

/**
 * The fields shown, in display order (spec R2-1, R2-2) [확정]: "전체" first, then the API's field codes ({@code drCode})
 * with our short names. A code not here is still kept: it is in "전체" and gets a list of its own after these, named
 * by the API's {@code drCodeName} (ADR-0089).
 */
public enum PickCategory {

	ALL("ALL", "전체"), LITERATURE("11", "문학"), HUMANITIES("6", "인문"), SOCIETY("5", "사회"), NATURE("4", "자연"),
	THEME("425", "테마");

	private final String code;

	private final String shortName;

	PickCategory(String code, String shortName) {
		this.code = code;
		this.shortName = shortName;
	}

	/** {@code ALL} or the API's {@code drCode}. */
	public String code() {
		return code;
	}

	public String shortName() {
		return shortName;
	}

	/** A field code of the API (never {@code ALL}). */
	public static Optional<PickCategory> byFieldCode(String code) {
		for (PickCategory category : values()) {
			if (category != ALL && category.code.equals(code)) {
				return Optional.of(category);
			}
		}
		return Optional.empty();
	}

}
