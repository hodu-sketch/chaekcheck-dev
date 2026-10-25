package com.chaekcheck.availability.internal.ranked;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Popular-order pages for the for-you build (cycle 23; docs/maintenance/settings-index.md).
 * @param kyoboPageSize {@code recordCount} on regular KYOBO sites: 80 is the largest the sites accept (pretest
 *     2026-10-24: 100 gave an error page) [안 80]. TODO(확인 필요): checkpoint B checks an 80-row page stays under
 *     {@code max-body-size} (1MB)
 * @param yes24PageSize {@code view} on YES24 V1 sites: 5/10/20/50 are offered [안 50]
 * @param requestDeadline one page, the lane's host-interval wait included [안 20s]
 */
@ConfigurationProperties("chaekcheck.availability.ranked")
public record RankedListingsProperties(@DefaultValue("80") int kyoboPageSize, @DefaultValue("50") int yes24PageSize,
		@DefaultValue("20s") Duration requestDeadline) {
}
