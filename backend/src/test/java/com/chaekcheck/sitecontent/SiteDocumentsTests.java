package com.chaekcheck.sitecontent;

import static com.chaekcheck.sitecontent.SitecontentTestData.TODAY;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import com.chaekcheck.sitecontent.internal.Today;

/** The current version rule for sign-up (plan cycle 16 step 3, C3; AC-3, AC-4 preparation). */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class SiteDocumentsTests {

	@Autowired
	SiteDocuments documents;

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
	void seedIsVersionOne() {
		assertThat(documents.currentVersion(ConsentDocument.TERMS_OF_SERVICE)).isEqualTo(1);
		assertThat(documents.currentVersion(ConsentDocument.PRIVACY_COLLECTION)).isEqualTo(1);
	}

	@Test
	void versionInEffectTodayIsCurrent() {
		data.documentVersion("TERMS_OF_SERVICE", 2, TODAY, "새 약관", null);

		assertThat(documents.currentVersion(ConsentDocument.TERMS_OF_SERVICE)).isEqualTo(2);
	}

	@Test
	void versionOfTomorrowIsNotCurrentYet() { // AC-4
		data.documentVersion("TERMS_OF_SERVICE", 2, TODAY.plusDays(1), "새 약관", null);

		assertThat(documents.currentVersion(ConsentDocument.TERMS_OF_SERVICE)).isEqualTo(1);
	}

	@Test
	void ofTwoVersionsOnTheSameDayTheHigherIsCurrent() {
		data.documentVersion("TERMS_OF_SERVICE", 2, TODAY, "둘째", null);
		data.documentVersion("TERMS_OF_SERVICE", 3, TODAY, "셋째", null);

		assertThat(documents.currentVersion(ConsentDocument.TERMS_OF_SERVICE)).isEqualTo(3);
	}

}
