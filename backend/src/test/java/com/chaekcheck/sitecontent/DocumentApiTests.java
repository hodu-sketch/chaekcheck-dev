package com.chaekcheck.sitecontent;

import static com.chaekcheck.sitecontent.SitecontentTestData.TODAY;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.transaction.annotation.Transactional;

import com.chaekcheck.sitecontent.internal.Today;

/** {@code GET /api/v1/documents/{documentKey}} (plan cycle 16 step 3; AC-2, AC-4, AC-9, AC-11). */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class DocumentApiTests {

	@Autowired
	MockMvcTester mvc;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	Today today;

	SitecontentTestData data;

	@BeforeEach
	void setUp() {
		SitecontentTestData.fixToday(today);
		data = new SitecontentTestData(jdbc);
	}

	@AfterEach
	void resetToday() {
		SitecontentTestData.resetToday(today);
	}

	@Test
	void theFourKeysAreFound() {
		for (String key : new String[] { "about", "terms-of-service", "privacy-policy", "privacy-collection" }) {
			assertThat(mvc.get().uri("/api/v1/documents/" + key)).as(key)
				.hasStatusOk()
				.bodyJson()
				.extractingPath("$.key")
				.isEqualTo(key);
		}
	}

	@Test
	void seedVersionOfTheTerms() {
		assertThat(mvc.get().uri("/api/v1/documents/terms-of-service")).hasStatusOk().bodyJson().isLenientlyEqualTo("""
				{"key":"terms-of-service",
				 "version":{"number":1,"effectiveOn":"2026-10-18","current":true,"changeSummary":null,"consentItems":null},
				 "versions":[{"number":1,"effectiveOn":"2026-10-18","supersededOn":null}]}""");
	}

	@Test
	void collectionNoticeHasItsFourItems() {
		assertThat(mvc.get().uri("/api/v1/documents/privacy-collection")).hasStatusOk()
			.bodyJson()
			.extractingPath("$.version.consentItems[*].label")
			.asArray()
			.containsExactly("목적", "항목", "보유 기간", "거부할 권리");
	}

	@Test
	void newAboutVersionIsCurrentAndTheOldOneStaysReadable() { // AC-2
		data.documentVersion("ABOUT", 2, TODAY, "새 안내", null);

		assertThat(mvc.get().uri("/api/v1/documents/about")).hasStatusOk().bodyJson().isLenientlyEqualTo("""
				{"version":{"number":2,"effectiveOn":"2026-10-28","current":true,"body":"새 안내"},
				 "versions":[{"number":2,"effectiveOn":"2026-10-28","supersededOn":null},
				             {"number":1,"effectiveOn":"2026-10-18","supersededOn":"2026-10-28"}]}""");
		assertThat(mvc.get().uri("/api/v1/documents/about?version=1")).hasStatusOk().bodyJson().isLenientlyEqualTo("""
				{"version":{"number":1,"effectiveOn":"2026-10-18","current":false}}""");
	}

	@Test
	void futureVersionIsNowhereBeforeItsDay() { // AC-4, AC-9
		// the terms: the privacy policy has a seeded version 2 (2026-10-20, the example card's IP line)
		data.documentVersion("TERMS_OF_SERVICE", 2, TODAY.plusDays(1), "내일 판", "바뀐 점");

		assertThat(mvc.get().uri("/api/v1/documents/terms-of-service")).hasStatusOk().bodyJson().isLenientlyEqualTo("""
				{"version":{"number":1,"current":true},
				 "versions":[{"number":1,"effectiveOn":"2026-10-18","supersededOn":null}]}""");
		assertNotFound("/api/v1/documents/terms-of-service?version=2", "version_not_found");
	}

	@Test
	void missingVersionIsNotFound() { // AC-9
		assertNotFound("/api/v1/documents/terms-of-service?version=99", "version_not_found");
	}

	@Test
	void unknownKeyIsNotFound() {
		assertNotFound("/api/v1/documents/nope", "document_not_found");
		assertNotFound("/api/v1/documents/ABOUT", "document_not_found");
	}

	@Test
	void changeSummaryIsGiven() { // AC-11
		data.documentVersion("TERMS_OF_SERVICE", 2, TODAY, "새 약관", "- 위탁 업체를 더했어요");

		assertThat(mvc.get().uri("/api/v1/documents/terms-of-service")).hasStatusOk()
			.bodyJson()
			.extractingPath("$.version.changeSummary")
			.isEqualTo("- 위탁 업체를 더했어요");
	}

	@Test
	void badVersionIsValidationError() {
		assertThat(mvc.get().uri("/api/v1/documents/about?version=0")).hasStatus(HttpStatus.BAD_REQUEST)
			.bodyJson()
			.isLenientlyEqualTo("""
					{"status":400,"code":"validation_error",
					 "errors":[{"field":"version","code":"Min","message":"must be greater than or equal to 1"}]}""");
		assertThat(mvc.get().uri("/api/v1/documents/about?version=abc")).hasStatus(HttpStatus.BAD_REQUEST)
			.bodyJson()
			.isLenientlyEqualTo("""
					{"code":"validation_error","errors":[{"field":"version","code":"typeMismatch"}]}""");
	}

	private void assertNotFound(String uri, String code) {
		assertThat(mvc.get().uri(uri)).as(uri)
			.hasStatus(HttpStatus.NOT_FOUND)
			.bodyJson()
			.extractingPath("$.code")
			.isEqualTo(code);
	}

}
