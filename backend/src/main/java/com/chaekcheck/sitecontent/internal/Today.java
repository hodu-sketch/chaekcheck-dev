package com.chaekcheck.sitecontent.internal;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;

import org.springframework.stereotype.Component;

/**
 * "Today" for the version rules: the Asia/Seoul date [확정] (plan cycle 16 design-delta "API"). Tests set a fixed
 * clock.
 */
@Component
public class Today {

	static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

	private volatile Clock clock = Clock.systemUTC();

	public LocalDate get() {
		return LocalDate.now(clock.withZone(ZONE));
	}

	/** For tests only. */
	public void setClock(Clock clock) {
		this.clock = clock;
	}

}
