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

/**
 * {@code GET /api/v1/notices} and {@code GET /api/v1/notices/{noticeId}} (plan cycle 16 step 3, C4; AC-1, AC-4, AC-5,
 * AC-9, AC-13). The seed has no notice, so the notices here are the only ones.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class NoticeApiTests {

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
	void noNoticeIsAnEmptyFirstPage() {
		assertThat(mvc.get().uri("/api/v1/notices")).hasStatusOk().bodyJson().isStrictlyEqualTo("""
				{"pinned":[],"items":[],"page":1,"pageSize":10,"totalCount":0,"hasMore":false}""");
		assertThat(mvc.get().uri("/api/v1/notices?page=5")).hasStatusOk()
			.bodyJson()
			.extractingPath("$.page")
			.isEqualTo(1);
	}

	@Test
	void newNoticeComesFirstAfterThePinnedOnes() { // AC-1
		long pinned = data.notice("고정 공지", TODAY.minusDays(30), true, false);
		long old = data.notice("옛 공지", TODAY.minusDays(3));
		long fresh = data.notice("새 공지", TODAY);

		assertThat(mvc.get().uri("/api/v1/notices")).hasStatusOk().bodyJson().isStrictlyEqualTo("""
				{"pinned":[{"id":%d,"title":"고정 공지","postedOn":"2026-09-28"}],
				 "items":[{"id":%d,"title":"새 공지","postedOn":"2026-10-28"},
				          {"id":%d,"title":"옛 공지","postedOn":"2026-10-25"}],
				 "page":1,"pageSize":10,"totalCount":2,"hasMore":false}""".formatted(pinned, fresh, old));
	}

	@Test
	void pinnedNoticesAreOnPageOneOnlyAndOutsideTheCount() {
		data.notice("고정", TODAY, true, false);
		for (int i = 1; i <= 23; i++) {
			data.notice("공지 " + i, TODAY.minusDays(i));
		}

		assertThat(mvc.get().uri("/api/v1/notices")).hasStatusOk()
			.bodyJson()
			.isLenientlyEqualTo("""
					{"page":1,"totalCount":23,"hasMore":true}""");
		assertThat(mvc.get().uri("/api/v1/notices")).bodyJson().extractingPath("$.items.length()").isEqualTo(10);
		assertThat(mvc.get().uri("/api/v1/notices")).bodyJson().extractingPath("$.pinned.length()").isEqualTo(1);
		assertThat(mvc.get().uri("/api/v1/notices?page=2")).bodyJson().extractingPath("$.pinned.length()").isEqualTo(0);
		assertThat(mvc.get().uri("/api/v1/notices?page=99")).hasStatusOk().bodyJson().isLenientlyEqualTo("""
				{"pinned":[],"page":3,"totalCount":23,"hasMore":false}""");
		assertThat(mvc.get().uri("/api/v1/notices?page=99")).bodyJson()
			.extractingPath("$.items[*].title")
			.asArray()
			.containsExactly("공지 21", "공지 22", "공지 23");
	}

	@Test
	void sameDayNoticesAreHigherNumberFirst() {
		long first = data.notice("먼저", TODAY);
		long second = data.notice("나중", TODAY);

		assertThat(mvc.get().uri("/api/v1/notices")).bodyJson()
			.extractingPath("$.items[*].id")
			.asArray()
			.containsExactly((int) second, (int) first);
	}

	@Test
	void noticePostedTomorrowIsNowhereYet() { // AC-4
		long tomorrow = data.notice("내일 공지", TODAY.plusDays(1));

		assertThat(mvc.get().uri("/api/v1/notices")).bodyJson().extractingPath("$.totalCount").isEqualTo(0);
		assertNotFound("/api/v1/notices/" + tomorrow, "notice_not_found");
	}

	@Test
	void hiddenNoticeIsNowhere() { // AC-5
		long hidden = data.notice("숨긴 공지", TODAY, false, true);
		data.notice("보이는 공지", TODAY);

		assertThat(mvc.get().uri("/api/v1/notices")).bodyJson()
			.extractingPath("$.items[*].title")
			.asArray()
			.containsExactly("보이는 공지");
		assertNotFound("/api/v1/notices/" + hidden, "notice_not_found");
		assertNotFound("/api/v1/notices/999999999", "notice_not_found");
	}

	@Test
	void editedNoticeShowsTheNewTitleAndItsEditedDayOnlyInTheNotice() { // AC-13
		long notice = data.notice("점검 안내", TODAY.minusDays(1));
		data.noticeVersion(notice, 2, TODAY, "점검 안내(시간 바뀜)", "새 본문");

		assertThat(mvc.get().uri("/api/v1/notices")).bodyJson().isStrictlyEqualTo("""
				{"pinned":[],"items":[{"id":%d,"title":"점검 안내(시간 바뀜)","postedOn":"2026-10-27"}],
				 "page":1,"pageSize":10,"totalCount":1,"hasMore":false}""".formatted(notice));
		assertThat(mvc.get().uri("/api/v1/notices/" + notice)).hasStatusOk().bodyJson().isStrictlyEqualTo("""
				{"id":%d,"title":"점검 안내(시간 바뀜)","postedOn":"2026-10-27","editedOn":"2026-10-28",
				 "version":{"number":2,"effectiveOn":"2026-10-28","current":true,"body":"새 본문"},
				 "versions":[{"number":2,"effectiveOn":"2026-10-28","supersededOn":null},
				             {"number":1,"effectiveOn":"2026-10-27","supersededOn":"2026-10-28"}]}""".formatted(notice));
	}

	@Test
	void oldVersionOfANotice() { // AC-9
		long notice = data.notice("점검 안내", TODAY.minusDays(1));
		data.noticeVersion(notice, 2, TODAY, "점검 안내(시간 바뀜)", "새 본문");
		data.noticeVersion(notice, 3, TODAY.plusDays(1), "내일 판", "내일 본문");

		assertThat(mvc.get().uri("/api/v1/notices/" + notice + "?version=1")).hasStatusOk()
			.bodyJson()
			.isLenientlyEqualTo("""
					{"title":"점검 안내","editedOn":"2026-10-28",
					 "version":{"number":1,"effectiveOn":"2026-10-27","current":false,"body":"본문 점검 안내"}}""");
		assertNotFound("/api/v1/notices/" + notice + "?version=3", "version_not_found");
		assertNotFound("/api/v1/notices/" + notice + "?version=9", "version_not_found");
	}

	@Test
	void unEditedNoticeHasNoEditedDay() {
		long notice = data.notice("공지", TODAY);

		assertThat(mvc.get().uri("/api/v1/notices/" + notice)).bodyJson().isLenientlyEqualTo("""
				{"postedOn":"2026-10-28","editedOn":null,"version":{"number":1,"current":true}}""");
	}

	@Test
	void badValuesAreValidationErrors() {
		for (String uri : new String[] { "/api/v1/notices?page=0", "/api/v1/notices?page=abc",
				"/api/v1/notices/abc", "/api/v1/notices/1?version=0" }) {
			assertThat(mvc.get().uri(uri)).as(uri)
				.hasStatus(HttpStatus.BAD_REQUEST)
				.bodyJson()
				.extractingPath("$.code")
				.isEqualTo("validation_error");
		}
	}

	private void assertNotFound(String uri, String code) {
		assertThat(mvc.get().uri(uri)).as(uri)
			.hasStatus(HttpStatus.NOT_FOUND)
			.bodyJson()
			.extractingPath("$.code")
			.isEqualTo(code);
	}

}
