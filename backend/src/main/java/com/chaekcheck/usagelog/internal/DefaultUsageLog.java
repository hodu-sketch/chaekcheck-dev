package com.chaekcheck.usagelog.internal;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.chaekcheck.auth.LoginState;
import com.chaekcheck.auth.LoginStatusReader;
import com.chaekcheck.usagelog.SearchConditions;
import com.chaekcheck.usagelog.UsageLog;
import com.chaekcheck.usagelog.UsageRequest;
import com.chaekcheck.usagelog.internal.buffer.UsageBuffer;
import com.chaekcheck.usagelog.internal.buffer.UsageRow;

/**
 * Checks and queues; never waits and never throws (plan C2). Off: every call returns at once and the login status is
 * not even read (C4). Robots are not recorded (C7); library answers are counted whoever asked. Since ADR-0134 the
 * capture also takes the account number (login on, signed in) and the tab's {@code X-Visit-Key}.
 */
public class DefaultUsageLog implements UsageLog {

	private static final Logger log = LoggerFactory.getLogger(DefaultUsageLog.class);

	private final boolean enabled;

	private final UsageBuffer buffer;

	private final LoginStatusReader loginStatus;

	private final Clock clock;

	private final ZoneId zone;

	public DefaultUsageLog(boolean enabled, UsageBuffer buffer, LoginStatusReader loginStatus, Clock clock,
			ZoneId zone) {
		this.enabled = enabled;
		this.buffer = buffer;
		this.loginStatus = loginStatus;
		this.clock = clock;
		this.zone = zone;
	}

	public boolean enabled() {
		return enabled;
	}

	@Override
	public UsageRequest capture(HttpServletRequest request) {
		if (!enabled) {
			return UsageRequest.NOT_RECORDED;
		}
		try {
			if (RequestFacts.isRobot(request)) {
				return UsageRequest.NOT_RECORDED;
			}
			LoginState login = loginStatus.read(request);
			return new UsageRequest(clock.instant(), RequestFacts.clientIp(request), login.status().value(),
					login.accountId(), RequestFacts.visitKey(request), true);
		}
		catch (RuntimeException ex) {
			log.warn("Usage log could not read the request: {}", ex.toString());
			return UsageRequest.NOT_RECORDED;
		}
	}

	@Override
	public void recordSearch(UsageRequest request, SearchKind kind, SearchConditions conditions, Integer resultCount) {
		if (request == null || !request.recorded()) {
			return;
		}
		offer(new UsageRow.Search(request.receivedAt(), name(kind), conditions, resultCount, request.loginStatus(),
				request.clientIp()));
	}

	@Override
	public void recordBookView(UsageRequest request, long bookId, List<Long> residenceRegionIds,
			List<Long> workRegionIds, List<Long> libraryIds) {
		if (request == null || !request.recorded()) {
			return;
		}
		try {
			offer(new UsageRow.BookView(request.receivedAt(), bookId, List.copyOf(residenceRegionIds),
					List.copyOf(workRegionIds), List.copyOf(libraryIds), request.loginStatus(), request.clientIp(),
					request.accountId(), request.visitKey()));
		}
		catch (RuntimeException ex) {
			log.warn("Usage log skipped a book view: {}", ex.toString());
		}
	}

	@Override
	public void recordMore(UsageRequest request, MoreKind kind, int seq, Long libraryId, SearchConditions conditions) {
		if (request == null || !request.recorded()) {
			return;
		}
		offer(new UsageRow.More(request.receivedAt(), name(kind), seq, libraryId, conditions, request.loginStatus(),
				request.clientIp()));
	}

	@Override
	public void recordLibraryResponse(long libraryId, ResponseSource source, ResponseResult result, long elapsedMs) {
		if (!enabled) {
			return;
		}
		try {
			buffer.addResponse(LocalDate.ofInstant(clock.instant(), zone), libraryId, source, result, elapsedMs);
		}
		catch (RuntimeException ex) {
			log.warn("Usage log skipped a library answer: {}", ex.toString());
		}
	}

	/** Rows from the usage report address (already checked). */
	public void offerReport(UsageRow row) {
		if (enabled) {
			offer(row);
		}
	}

	private void offer(UsageRow row) {
		try {
			buffer.offer(row);
		}
		catch (RuntimeException ex) {
			log.warn("Usage log skipped a row: {}", ex.toString());
		}
	}

	private static String name(Enum<?> value) {
		return value.name().toLowerCase(Locale.ROOT);
	}

}
