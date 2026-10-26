package com.chaekcheck.aisearch.internal.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import com.chaekcheck.aisearch.internal.flow.FakeLlm;
import com.chaekcheck.aisearch.internal.llm.LlmClient;

/**
 * {@code /api/v1/ai-searches} through MockMvc with the feature on and a fake model (cycle 24 handoff step 8-3): the
 * refusals before the stream opens (400 validation_error and invalid_query, 422 invalid_region, 429 with Retry-After,
 * 503 ai_search_paused and server_busy), the stream's headers and its done event, the status answer, and no question
 * text in the logs or the errors (AC-7, AC-8, AC-9, AC-16). The IP limit is 3 a minute here.
 */
@SpringBootTest(properties = { "chaekcheck.aisearch.enabled=true", "chaekcheck.aisearch.rate.per-minute=3" })
@AutoConfigureMockMvc
@Import(AiSearchControllerTests.FakeModel.class)
@ExtendWith(OutputCaptureExtension.class)
class AiSearchControllerTests {

	static final String URL = "/api/v1/ai-searches/stream";

	static final String QUESTION = "비밀스러운 질문 글 문장";

	@TestConfiguration
	static class FakeModel {

		@Bean
		@Primary
		LlmClient fakeLlm() {
			// no planned answer: every call fails as NOT_CONFIGURED, so the search takes its fallback way
			return new FakeLlm();
		}

	}

	@Autowired
	MockMvcTester mvc;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	AiSearchSlots slots;

	@Autowired
	com.chaekcheck.library.LibraryCatalog libraryCatalog;

	@Autowired
	com.chaekcheck.aisearch.AiSearchBookTexts bookTexts;

	@Autowired
	com.chaekcheck.aisearch.internal.quota.DailyBudget budget;

	/** The quota lives as long as the context: every request takes a new IP unless it means to share one. */
	private static final java.util.concurrent.atomic.AtomicInteger IPS = new java.util.concurrent.atomic.AtomicInteger();

	@BeforeEach
	@AfterEach
	void clean() {
		jdbc.sql("DELETE FROM aisearch.daily_usage").update();
	}

	@Test
	void theStatusSaysOn() {
		assertThat(mvc.get().uri("/api/v1/ai-searches/status").exchange()).hasStatusOk().bodyJson().isEqualTo("{}");
	}

	@Test
	void aSearchStreamsToItsDoneWithoutTheQuestionInTheLogs(CapturedOutput output) {
		MvcTestResult result = post(body(QUESTION, 1, "[]"), nextIp());

		assertThat(result).hasStatusOk().hasContentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM);
		assertThat(result).hasHeader("X-Accel-Buffering", "no");
		assertThat(new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8))
			.contains("event:done\ndata:{\"result\":\"no_match\",\"bookIds\":[]}");
		assertThat(output.getAll()).contains("ai search: first haiku, keyword FAILED").doesNotContain(QUESTION);
		assertThat(jdbc.sql("SELECT request_count FROM aisearch.daily_usage").query(Integer.class).single()).isEqualTo(1);
	}

	@Test
	void theQuestionIsTwoToTwoHundredCharacters() { // AC-16
		assertProblem(post(body(" 책 ", 1, "[]"), nextIp()), HttpStatus.BAD_REQUEST, "invalid_query");
		assertProblem(post(body("가".repeat(201), 1, "[]"), nextIp()), HttpStatus.BAD_REQUEST, "invalid_query");
		assertThat(post(body("가".repeat(200), 1, "[]"), nextIp())).hasStatusOk();
	}

	@Test
	void aWrongBodyIsAValidationErrorWithoutTheQuestion() {
		MvcTestResult notJson = post("{\"question\":\"" + QUESTION + "\",", nextIp());
		assertProblem(notJson, HttpStatus.BAD_REQUEST, "validation_error");
		assertThat(new String(notJson.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8))
			.doesNotContain(QUESTION);
		assertProblem(post(body(QUESTION, 0, "[]"), nextIp()), HttpStatus.BAD_REQUEST, "validation_error");
		assertProblem(post(body(QUESTION, 21, "[]"), nextIp()), HttpStatus.BAD_REQUEST, "validation_error");
		assertProblem(post(body(QUESTION, 2, "[" + "{\"question\":\"앞 질문\",\"bookIds\":[1]},".repeat(5)
				+ "{\"question\":\"여섯째\",\"bookIds\":[]}]"), nextIp()), HttpStatus.BAD_REQUEST, "validation_error");
		assertProblem(post(body(QUESTION, 2, "[{\"question\":\"앞\",\"bookIds\":[1,2,3,4,5,6,7,8,9]}]"), nextIp()),
				HttpStatus.BAD_REQUEST, "validation_error");
		assertProblem(post("{\"question\":\"" + QUESTION + "\",\"turnNo\":1,\"scope\":\"some\"}", nextIp()),
				HttpStatus.BAD_REQUEST, "validation_error");
		// an earlier ask-back goes back with its turn (user 2026-10-27); at most four choices
		assertThat(post(body(QUESTION, 2, "[{\"question\":\"그림\",\"bookIds\":[],\"askQuestion\":\"어떤 그림 책이에요?\","
				+ "\"askChoices\":[\"그리는 법\",\"명화 감상\"]}]"), nextIp())).hasStatusOk();
		assertProblem(post(body(QUESTION, 2, "[{\"question\":\"그림\",\"bookIds\":[],\"askQuestion\":\"어느 쪽?\","
				+ "\"askChoices\":[\"가\",\"나\",\"다\",\"라\",\"마\"]}]"), nextIp()), HttpStatus.BAD_REQUEST, "validation_error");
	}

	@Test
	void aBodyOverSixteenKilobytesIsRefusedEvenWithoutItsLength() {
		String big = body(QUESTION, 1, "[]").replace("}", ",\"pad\":\"" + "가".repeat(6_000) + "\"}");
		assertProblem(post(big, nextIp()), HttpStatus.BAD_REQUEST, "validation_error");
	}

	@Test
	void ipv6AddressesOfOneSlash64ShareTheirCount() {
		int n = IPS.incrementAndGet();
		String prefix = "2001:db8:" + Integer.toHexString(n) + ":1:";
		for (int i = 1; i <= 3; i++) {
			assertThat(post(body(QUESTION, 1, "[]"), prefix + i)).hasStatusOk();
		}
		assertProblem(post(body(QUESTION, 1, "[]"), prefix + "ffff"), HttpStatus.TOO_MANY_REQUESTS, "too_many_requests");
	}

	@Test
	void anUnknownRegionIs422() {
		assertProblem(post("{\"question\":\"" + QUESTION + "\",\"turnNo\":1,\"residenceRegionIds\":[999999999]}", nextIp()),
				HttpStatus.UNPROCESSABLE_CONTENT, "invalid_region");
	}

	@Test
	void theFourthSearchInAMinuteFromOneIpWaits() { // AC-8 with 3 a minute
		String ip = nextIp();
		for (int i = 0; i < 3; i++) {
			assertThat(post(body(QUESTION, 1, "[]"), ip)).hasStatusOk();
		}

		MvcTestResult fourth = post(body(QUESTION, 1, "[]"), ip);

		assertProblem(fourth, HttpStatus.TOO_MANY_REQUESTS, "too_many_requests");
		assertThat(Long.parseLong(fourth.getResponse().getHeader("Retry-After"))).isBetween(1L, 60L);
		assertThat(post(body(QUESTION, 1, "[]"), nextIp())).hasStatusOk();
		budget.flushRefusals();
		assertThat(jdbc.sql("SELECT rate_limited_count FROM aisearch.daily_usage").query(Integer.class).single())
			.isEqualTo(1);
	}

	@Test
	void aSearchAfterTheDaysCapRests() { // AC-9
		jdbc.sql("INSERT INTO aisearch.daily_usage (day, cost_micro_usd) VALUES (:day, 2000000)")
			.param("day", LocalDate.now(ZoneId.of("Asia/Seoul")))
			.update();

		assertProblem(post(body(QUESTION, 1, "[]"), nextIp()), HttpStatus.SERVICE_UNAVAILABLE, "ai_search_paused");
		budget.flushRefusals();
		assertThat(jdbc.sql("SELECT paused_count FROM aisearch.daily_usage").query(Integer.class).single()).isEqualTo(1);
	}

	@Test
	void everySlotTakenIsServerBusy() {
		int taken = 0;
		while (slots.tryAcquire()) {
			taken++;
		}
		try {
			MvcTestResult busy = post(body(QUESTION, 1, "[]"), nextIp());
			assertProblem(busy, HttpStatus.SERVICE_UNAVAILABLE, "server_busy");
			assertThat(busy.getResponse().getHeader("Retry-After")).isEqualTo("5");
		}
		finally {
			for (int i = 0; i < taken; i++) {
				slots.release();
			}
		}
	}

	@Test
	@org.springframework.transaction.annotation.Transactional
	void theCountsFollowThePlanForTheBooksAlreadyShown() { // user 2026-10-25: a region changed after the answer
		long library = libraryCatalog
			.planSearch(java.util.Set.of(), java.util.Set.of(),
					new com.chaekcheck.library.PlanOptions(com.chaekcheck.library.PlanOptions.Scope.JOINABLE,
							com.chaekcheck.library.PlanOptions.Visit.ANY))
			.getFirst()
			.id();
		bookTexts.replace(java.util.List.of(new com.chaekcheck.aisearch.AiSearchBookTexts.Book(1, "가", null,
				new long[] { library, 999_999_999 }), new com.chaekcheck.aisearch.AiSearchBookTexts.Book(2, "나", null,
						new long[] { 999_999_999 })), java.util.List.of());

		MvcTestResult counts = countsPost("{\"bookIds\":[1,2,3],\"scope\":\"joinable\"}");

		assertThat(counts).hasStatusOk().bodyJson().isEqualTo("{\"counts\":{\"1\":1,\"2\":0,\"3\":0}}");
		assertThat(countsPost("{\"bookIds\":[1],\"libraryIds\":[" + library + "]}")).hasStatusOk()
			.bodyJson()
			.extractingPath("$.counts.1")
			.isEqualTo(1);
	}

	@Test
	void theCountsTakeAtMost160BooksAndKnownRegions() {
		StringBuilder ids = new StringBuilder("1");
		for (int i = 2; i <= 161; i++) {
			ids.append(',').append(i);
		}
		assertProblem(countsPost("{\"bookIds\":[" + ids + "]}"), HttpStatus.BAD_REQUEST, "validation_error");
		assertProblem(countsPost("{\"bookIds\":[1],\"residenceRegionIds\":[999999999]}"),
				HttpStatus.UNPROCESSABLE_CONTENT, "invalid_region");
		assertProblem(countsPost("{\"bookIds\":[0]}"), HttpStatus.BAD_REQUEST, "validation_error");
	}

	private MvcTestResult countsPost(String json) {
		return mvc.post()
			.uri("/api/v1/ai-searches/library-counts")
			.contentType(MediaType.APPLICATION_JSON)
			.header("X-Real-IP", nextIp())
			.content(json)
			.exchange();
	}

	private MvcTestResult post(String json, String ip) {
		return mvc.post()
			.uri(URL)
			.contentType(MediaType.APPLICATION_JSON)
			.header("X-Real-IP", ip)
			.content(json)
			.exchange(Duration.ofSeconds(10));
	}

	private String nextIp() {
		int n = IPS.incrementAndGet();
		return "198.51." + (n / 250) + "." + (n % 250 + 1);
	}

	private static String body(String question, int turnNo, String history) {
		return "{\"question\":\"" + question + "\",\"turnNo\":" + turnNo + ",\"history\":" + history + "}";
	}

	static void assertProblem(MvcTestResult result, HttpStatus status, String code) {
		assertThat(result).hasStatus(status).hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
		assertThat(result).bodyJson().extractingPath("$.code").isEqualTo(code);
	}

}
