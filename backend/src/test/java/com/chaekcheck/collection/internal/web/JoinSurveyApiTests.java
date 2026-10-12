package com.chaekcheck.collection.internal.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.transaction.annotation.Transactional;

/** Plan cycle 13 handoff step 6: {@code GET /api/v1/join-surveys/latest} (ADR-0093). */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class JoinSurveyApiTests {

	@Autowired
	MockMvcTester mvc;

	@Autowired
	JdbcClient jdbc;

	@Test
	void nullBeforeAnyInsert() {
		jdbc.sql("DELETE FROM collection.library_insert_run").update();
		assertThat(mvc.get().uri("/api/v1/join-surveys/latest")).hasStatusOk()
			.bodyJson()
			.isLenientlyEqualTo("{\"surveyedOn\": null}");
	}

	@Test
	void theLatestSucceededDate() {
		run("0123456789aa", "2026-04-01", "SUCCEEDED", null);
		run("0123456789ab", "2026-10-05", "SUCCEEDED", null);
		run("0123456789ac", "2026-12-31", "FAILED", "refused"); // a failed run does not count
		assertThat(mvc.get().uri("/api/v1/join-surveys/latest")).hasStatusOk()
			.bodyJson()
			.isLenientlyEqualTo("{\"surveyedOn\": \"2026-10-05\"}");
	}

	private void run(String reportId, String surveyedOn, String status, String detail) {
		jdbc.sql("""
				INSERT INTO collection.library_insert_run
				  (report_id, snapshot, inserted_count, join_surveyed_on, status, failure_detail, payload)
				VALUES (:reportId, 'copy.sqlite', 1, CAST(:surveyedOn AS date), :status, :detail, '{}')""")
			.param("reportId", reportId)
			.param("surveyedOn", surveyedOn)
			.param("status", status)
			.param("detail", detail)
			.update();
	}

}
