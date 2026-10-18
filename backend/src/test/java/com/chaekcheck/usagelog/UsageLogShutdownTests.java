package com.chaekcheck.usagelog;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.chaekcheck.ChaekcheckApplication;

/**
 * A normal shutdown writes the rows still waiting (AC-15, spec R3-4). A second application with a real web server is
 * started and closed here: the flusher must stop after the web server and before the data source closes. Its
 * scheduled flush is pushed out of the way, so only the shutdown can write. Read through the default test context.
 */
@SpringBootTest
class UsageLogShutdownTests {

	@Autowired
	JdbcClient jdbc;

	@Test
	void shutdownWritesTheRowsLeft() {
		jdbc.sql("DELETE FROM usagelog.book_view WHERE book_id = 4242").update();
		// command-line arguments: builder properties are only defaults and lose to application-test.yml
		ConfigurableApplicationContext app = new SpringApplicationBuilder(ChaekcheckApplication.class).run(
				"--server.port=0", "--chaekcheck.usagelog.enabled=true", "--chaekcheck.usagelog.flush-interval=1h");
		try {
			app.getBean(UsageLog.class)
				.recordBookView(new UsageRequest(Instant.now(), null, "login_off", null, null, true), 4242, List.of(), List.of(),
						List.of());
			assertThat(count()).isZero();
		}
		finally {
			app.close();
		}

		assertThat(count()).isEqualTo(1);
	}

	private long count() {
		return jdbc.sql("SELECT count(*) FROM usagelog.book_view WHERE book_id = 4242").query(Long.class).single();
	}

}
