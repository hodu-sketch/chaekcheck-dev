package com.chaekcheck.availability.internal.adapter;

import org.springframework.stereotype.Component;

import com.chaekcheck.availability.internal.config.AvailabilityProperties;
import com.chaekcheck.library.PlatformType;

/**
 * Regular KYOBO e-library, e.g.
 * {@code https://www.ghlib.go.kr:446/elibrary-front/search/searchList.ink?schClst=ctts&schDvsn=000&schTxt=..&recordCount=10&pageIndex=N}.
 * Rows show holdings, loaned and reservations.
 */
@Component
public class KyoboV1Adapter extends KyoboV1ScreenAdapter {

	public KyoboV1Adapter(AvailabilityProperties properties) {
		super(properties.kyobo().recordCount());
	}

	@Override
	public PlatformType platform() {
		return PlatformType.KYOBO_V1;
	}

	@Override
	String pathPrefix() {
		return "/elibrary-front";
	}

	@Override
	boolean hasCounts() {
		return true;
	}

}
