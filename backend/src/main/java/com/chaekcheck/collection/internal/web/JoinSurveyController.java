package com.chaekcheck.collection.internal.web;

import java.time.LocalDate;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/v1/join-surveys/latest} (plan cycle 13, ADR-0087, ADR-0093): the date of the join-condition survey
 * behind the libraries, for the line on {@code /about}. Public, no login. The latest date among the succeeded
 * library-insert runs; {@code {"surveyedOn": null}} before any. The production apply copies the run rows, so the
 * value is there too.
 */
@RestController
class JoinSurveyController {

	private final JdbcClient jdbc;

	JoinSurveyController(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@GetMapping("/api/v1/join-surveys/latest")
	@Transactional(readOnly = true)
	LatestJoinSurvey latest() {
		return new LatestJoinSurvey(jdbc.sql("""
				SELECT max(join_surveyed_on) FROM collection.library_insert_run WHERE status = 'SUCCEEDED'""")
			.query(LocalDate.class)
			.optional()
			.orElse(null));
	}

	/** @param surveyedOn {@code 2026-10-05}, or null when no insert has run */
	record LatestJoinSurvey(LocalDate surveyedOn) {
	}

}
