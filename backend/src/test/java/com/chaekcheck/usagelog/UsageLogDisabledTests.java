package com.chaekcheck.usagelog;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletRequest;

import com.chaekcheck.usagelog.UsageLog.MoreKind;
import com.chaekcheck.usagelog.UsageLog.ResponseResult;
import com.chaekcheck.usagelog.UsageLog.ResponseSource;
import com.chaekcheck.usagelog.UsageLog.SearchKind;
import com.chaekcheck.usagelog.internal.buffer.UsageBuffer;
import com.chaekcheck.usagelog.internal.write.UsageLogFlusher;

/**
 * Logging off, the default (AC-21 off part, plan C4): every record returns at once and a flush writes nothing to any
 * of the eight tables. The on part is {@link UsageLogWriteTests}. Uses the default test context.
 */
@SpringBootTest
class UsageLogDisabledTests {

	@Autowired
	UsageLog usageLog;

	@Autowired
	UsageBuffer buffer;

	@Autowired
	UsageLogFlusher flusher;

	@Autowired
	JdbcClient jdbc;

	@Test
	void offRecordsNothing() {
		UsageLogTestTables.empty(jdbc);
		MockHttpServletRequest browser = new MockHttpServletRequest();
		browser.addHeader("X-Real-IP", "203.0.113.7");
		UsageRequest request = usageLog.capture(browser);
		SearchConditions conditions = SearchConditions.direct("채식주의자", null, null, "all", "all", "any", List.of(),
				List.of(), List.of());

		usageLog.recordSearch(request, SearchKind.DIRECT, conditions, 3);
		usageLog.recordBookView(request, 345, List.of(), List.of(), List.of());
		usageLog.recordMore(request, MoreKind.DIRECT_NEXT_BATCH, 2, null, conditions);
		usageLog.recordLibraryResponse(1, ResponseSource.DIRECT_SEARCH, ResponseResult.OK, 100);
		flusher.flush();

		assertThat(request.recorded()).isFalse();
		assertThat(buffer.size()).isZero();
		for (String table : UsageLogTestTables.TABLES) {
			assertThat(UsageLogTestTables.count(jdbc, table)).as(table).isZero();
		}
	}

}
