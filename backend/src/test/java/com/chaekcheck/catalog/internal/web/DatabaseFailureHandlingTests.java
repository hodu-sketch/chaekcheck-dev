package com.chaekcheck.catalog.internal.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.transaction.CannotCreateTransactionException;

import com.chaekcheck.availability.LookupHealth;
import com.chaekcheck.catalog.internal.search.BookSearch;
import com.chaekcheck.catalog.BookTexts;
import com.chaekcheck.library.LibraryCatalog;
import com.chaekcheck.usagelog.UsageLog;

/**
 * Cycle 20 (ADR-0118, design review #3, AC-37 MVC part): a request that fails on the database is still the same
 * 500 {@code internal_error} as before, and the root {@code ApiExceptionHandler} reports it to {@code LookupHealth}
 * once, for both exception types a down database produces. A web slice (no database, no context of its own with a
 * connection pool); the book search controller is just the request that reaches the handler.
 */
@WebMvcTest(BookSearchController.class)
@Import(DatabaseFailureHandlingTests.Settings.class)
class DatabaseFailureHandlingTests {

	@TestConfiguration
	static class Settings {

		@Bean
		BookSearchSettings bookSearchSettings() {
			return new BookSearchSettings(10);
		}

	}

	@Autowired
	MockMvcTester mvc;

	@MockitoBean
	BookSearch bookSearch;

	@MockitoBean
	LibraryCatalog libraryCatalog;

	@MockitoBean
	UsageLog usageLog;

	@MockitoBean
	LookupHealth lookupHealth;

	@MockitoBean
	BookTexts bookTexts;

	@Test
	void connectionFailureIsInternalErrorAndRecorded() {
		given(libraryCatalog.planSearch(any(), any(), any()))
			.willThrow(new CannotGetJdbcConnectionException("Failed to obtain JDBC Connection"));

		assertThat(mvc.get().uri("/api/v1/books?title=사랑")).hasStatus(500)
			.bodyJson()
			.isLenientlyEqualTo("{\"status\":500,\"detail\":\"Unexpected error\",\"code\":\"internal_error\"}");
		verify(lookupHealth, times(1)).recordDbError();
	}

	@Test
	void transactionStartFailureIsInternalErrorAndRecorded() {
		given(libraryCatalog.planSearch(any(), any(), any()))
			.willThrow(new CannotCreateTransactionException("Could not open JDBC Connection for transaction"));

		assertThat(mvc.get().uri("/api/v1/books?title=사랑")).hasStatus(500)
			.bodyJson()
			.extractingPath("$.code")
			.isEqualTo("internal_error");
		verify(lookupHealth, times(1)).recordDbError();
	}

	@Test
	void otherUnexpectedErrorsAreNotCountedAsDbErrors() {
		given(libraryCatalog.planSearch(any(), any(), any())).willThrow(new IllegalStateException("boom"));

		assertThat(mvc.get().uri("/api/v1/books?title=사랑")).hasStatus(500)
			.bodyJson()
			.extractingPath("$.code")
			.isEqualTo("internal_error");
		verify(lookupHealth, times(0)).recordDbError();
	}

}
