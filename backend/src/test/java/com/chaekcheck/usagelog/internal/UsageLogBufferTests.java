package com.chaekcheck.usagelog.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import com.chaekcheck.auth.LoginStatus;
import com.chaekcheck.usagelog.SearchConditions;
import com.chaekcheck.usagelog.UsageLog.ResponseResult;
import com.chaekcheck.usagelog.UsageLog.ResponseSource;
import com.chaekcheck.usagelog.UsageLog.SearchKind;
import com.chaekcheck.usagelog.UsageRequest;
import com.chaekcheck.usagelog.internal.buffer.UsageBuffer;
import com.chaekcheck.usagelog.internal.buffer.UsageRow;

/** The in-memory side (cycle 15 step 3): queue limit, library answer counts, robots, off, IP reading. */
class UsageLogBufferTests {

	private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

	/** 2026-10-14 23:59:59 in Seoul: still the 14th there, the 14th in UTC too. */
	private final Clock clock = Clock.fixed(Instant.parse("2026-10-14T14:59:59Z"), ZoneOffset.UTC);

	private static final SearchConditions CONDITIONS = SearchConditions.direct("채식주의자", null, null, "all", "all",
			"any", List.of(), List.of(), List.of());

	@Test
	void rowPastTheLimitIsDroppedAndCounted() {
		UsageBuffer buffer = new UsageBuffer(10_000);
		UsageRow row = new UsageRow.Rejected(Instant.EPOCH, null, "malformed", null);
		for (int i = 0; i < 10_000; i++) {
			assertThat(buffer.offer(row)).isTrue();
		}

		assertThat(buffer.offer(row)).isFalse();
		assertThat(buffer.offer(row)).isFalse();

		UsageBuffer.Batch batch = buffer.drain();
		assertThat(batch.rows()).hasSize(10_000);
		assertThat(batch.droppedWhileFull()).isEqualTo(2);
		assertThat(buffer.drain().isEmpty()).isTrue();
	}

	@Test
	void libraryAnswersAddUpPerDayLibraryAndSource() {
		UsageBuffer buffer = new UsageBuffer(10);
		LocalDate day = LocalDate.of(2026, 10, 14);
		buffer.addResponse(day, 7, ResponseSource.DIRECT_SEARCH, ResponseResult.OK, 120);
		buffer.addResponse(day, 7, ResponseSource.DIRECT_SEARCH, ResponseResult.OK, 900);
		buffer.addResponse(day, 7, ResponseSource.DIRECT_SEARCH, ResponseResult.FAILED, 50);
		buffer.addResponse(day, 7, ResponseSource.DIRECT_SEARCH, ResponseResult.TIMEOUT, 8000);
		buffer.addResponse(day, 7, ResponseSource.DIRECT_SEARCH, ResponseResult.SKIPPED, 0);
		buffer.addResponse(day, 7, ResponseSource.HOLDING_FILL, ResponseResult.OK, 30);
		// cycle 19: a cached answer counts only in cached, its time is ignored
		buffer.addResponse(day, 7, ResponseSource.DIRECT_SEARCH, ResponseResult.CACHED, 5000);
		buffer.addResponse(day, 7, ResponseSource.HOLDING_FILL, ResponseResult.CACHED, 0);

		UsageBuffer.Batch batch = buffer.drain();

		assertThat(batch.responses()).containsEntry(
				new UsageBuffer.ResponseKey(day, 7, ResponseSource.DIRECT_SEARCH),
				new UsageBuffer.ResponseTotals(2, 1, 1, 1, 1020, 900, 1));
		assertThat(batch.responses()).containsEntry(new UsageBuffer.ResponseKey(day, 7, ResponseSource.HOLDING_FILL),
				new UsageBuffer.ResponseTotals(1, 0, 0, 0, 30, 30, 1));
		assertThat(batch.rows()).isEmpty();
	}

	@Test
	void robotsAreNotRecorded() { // AC-6 (unit)
		UsageBuffer buffer = new UsageBuffer(10);
		DefaultUsageLog usageLog = new DefaultUsageLog(true, buffer, request -> LoginStatus.SIGNED_OUT, clock, SEOUL);

		for (String agent : List.of("Mozilla/5.0 (compatible; Googlebot/2.1)", "SomeCrawler/1.0",
				"Baiduspider", "BOT")) {
			MockHttpServletRequest request = new MockHttpServletRequest();
			request.addHeader("User-Agent", agent);
			UsageRequest captured = usageLog.capture(request);
			assertThat(captured.recorded()).as(agent).isFalse();
			usageLog.recordSearch(captured, SearchKind.DIRECT, CONDITIONS, 3);
		}

		assertThat(buffer.size()).isZero();
	}

	@Test
	void browsersAreRecordedWithTimeIpAndLoginStatus() {
		UsageBuffer buffer = new UsageBuffer(10);
		DefaultUsageLog usageLog = new DefaultUsageLog(true, buffer, request -> LoginStatus.SIGNED_IN, clock, SEOUL);
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 14; SM-S921N) SamsungBrowser/26.0");
		request.addHeader("X-Real-IP", "203.0.113.7");

		usageLog.recordSearch(usageLog.capture(request), SearchKind.DIRECT, CONDITIONS, null);

		assertThat(buffer.drain().rows()).singleElement()
			.isEqualTo(new UsageRow.Search(clock.instant(), "direct", CONDITIONS, null, "signed_in", "203.0.113.7"));
	}

	@Test
	void offRecordsNothingAndDoesNotReadTheLogin() { // C4
		UsageBuffer buffer = new UsageBuffer(10);
		DefaultUsageLog usageLog = new DefaultUsageLog(false, buffer, request -> {
			throw new AssertionError("login status read while off");
		}, clock, SEOUL);

		UsageRequest captured = usageLog.capture(new MockHttpServletRequest());
		usageLog.recordSearch(captured, SearchKind.DIRECT, CONDITIONS, 1);
		usageLog.recordBookView(captured, 1, List.of(), List.of(), List.of());
		usageLog.recordLibraryResponse(1, ResponseSource.DIRECT_SEARCH, ResponseResult.OK, 10);

		assertThat(captured.recorded()).isFalse();
		assertThat(buffer.drain().isEmpty()).isTrue();
	}

	@Test
	void libraryAnswerDayIsTheSeoulDay() {
		UsageBuffer buffer = new UsageBuffer(10);
		// 2026-10-14 15:00:00 UTC is already the 15th in Seoul
		Clock late = Clock.fixed(Instant.parse("2026-10-14T15:00:00Z"), ZoneOffset.UTC);
		new DefaultUsageLog(true, buffer, request -> LoginStatus.LOGIN_OFF, late, SEOUL).recordLibraryResponse(3,
				ResponseSource.HOLDING_FILL, ResponseResult.OK, 5);

		assertThat(buffer.drain().responses()).containsOnlyKeys(
				new UsageBuffer.ResponseKey(LocalDate.of(2026, 10, 15), 3, ResponseSource.HOLDING_FILL));
	}

	@Test
	void onlyIpAddressesAreKept() {
		assertThat(ip("203.0.113.7")).isEqualTo("203.0.113.7");
		assertThat(ip(" 10.0.0.1 ")).isEqualTo("10.0.0.1");
		assertThat(ip("2001:db8::1")).isEqualTo("2001:db8:0:0:0:0:0:1");
		assertThat(ip(null)).isNull();
		assertThat(ip("")).isNull();
		assertThat(ip("256.1.1.1")).isNull();
		assertThat(ip("example.com")).isNull();
		assertThat(ip("203.0.113.7, 10.0.0.1")).isNull();
		assertThat(ip("::zz")).isNull();
	}

	private static String ip(String header) {
		MockHttpServletRequest request = new MockHttpServletRequest();
		if (header != null) {
			request.addHeader("X-Real-IP", header);
		}
		return RequestFacts.clientIp(request);
	}

}
