package com.chaekcheck.availability.internal;

import com.chaekcheck.library.Eligibility;
import com.chaekcheck.library.JoinSurvey;
import com.chaekcheck.library.LibraryType;
import com.chaekcheck.library.PlatformType;
import com.chaekcheck.library.PriorityGroup;
import com.chaekcheck.library.SearchTarget;
import com.chaekcheck.library.Vendor;

/** Search targets for availability tests: an open, non-deferred e-library. */
public final class TestTargets {

	public static final SearchTarget GANGSEO = target(1, PlatformType.YES24_V2, "https://ebook.gangseo.seoul.kr");

	public static final SearchTarget GOHEUNG = target(2, PlatformType.KYOBO_V1, "https://www.ghlib.go.kr:446");

	private TestTargets() {
	}

	public static SearchTarget target(long id, PlatformType platformType, String baseUrl) {
		return new SearchTarget(id, "lib" + id, LibraryType.EBOOK, platformType, baseUrl, PriorityGroup.OPEN, false,
				Eligibility.OPEN, null, null, false, "join", null, null, null, null, false, JoinSurvey.SURVEYED, null,
				null, false, vendorOf(platformType), 1);
	}

	/** Same table as library/014 and the seed script (ADR-0071). */
	public static Vendor vendorOf(PlatformType platformType) {
		return switch (platformType) {
			case KYOBO_V1, KYOBO_SUBSCRIPTION_V1 -> Vendor.KYOBO;
			case YES24_V1, YES24_V2, YES24_V3 -> Vendor.YES24;
			case BOOKCUBE_FX -> Vendor.BOOKCUBE;
			case ALADIN_V1 -> Vendor.ALADIN;
			case ELIB_PORTAL_V1 -> Vendor.PORTAL;
			case GANGNAM_V1 -> Vendor.IN_HOUSE;
		};
	}

}
