package com.chaekcheck.availability.internal.adapter;

import org.springframework.stereotype.Component;

import com.chaekcheck.availability.internal.config.AvailabilityProperties;
import com.chaekcheck.library.PlatformType;

/**
 * KYOBO subscription e-library (e.g. {@code https://sejonglib.dkyobobook.co.kr}): no {@code /elibrary-front}
 * prefix, and rows have no counts because any number of users can borrow a book at once. The search page does not
 * show whether the monthly budget is used up (checked 2026-09-28: every row shows a "대출" button).
 */
@Component
public class KyoboSubscriptionV1Adapter extends KyoboV1ScreenAdapter {

	public KyoboSubscriptionV1Adapter(AvailabilityProperties properties) {
		super(properties.kyobo().recordCount());
	}

	@Override
	public PlatformType platform() {
		return PlatformType.KYOBO_SUBSCRIPTION_V1;
	}

	@Override
	String pathPrefix() {
		return "";
	}

	@Override
	boolean hasCounts() {
		return false;
	}

}
