package com.chaekcheck.availability.internal;

import com.chaekcheck.library.Eligibility;
import com.chaekcheck.library.LibraryType;
import com.chaekcheck.library.PlatformType;
import com.chaekcheck.library.PriorityGroup;
import com.chaekcheck.library.SearchTarget;

/** Search targets for availability tests: an open, non-deferred e-library. */
public final class TestTargets {

	public static final SearchTarget GANGSEO = target(1, PlatformType.YES24_V2, "https://ebook.gangseo.seoul.kr");

	public static final SearchTarget GOHEUNG = target(2, PlatformType.KYOBO_V1, "https://www.ghlib.go.kr:446");

	private TestTargets() {
	}

	public static SearchTarget target(long id, PlatformType platformType, String baseUrl) {
		return new SearchTarget(id, "lib" + id, LibraryType.EBOOK, platformType, baseUrl, PriorityGroup.OPEN, false,
				Eligibility.OPEN, null, null, false, "join", null, null, null, null, false);
	}

}
