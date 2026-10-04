package com.chaekcheck.mylibrary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MockMvcTester.MockMvcRequestBuilder;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.support.TransactionTemplate;

import com.chaekcheck.auth.AccountDeleted;
import com.chaekcheck.auth.AccountId;
import com.chaekcheck.auth.internal.account.AccountDeletion;
import com.chaekcheck.mylibrary.internal.MyLibraryService;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

/**
 * Cycle 06 step 5: the "내 도서관" APIs with login on, against db-test. No test transaction: the account lock, the
 * concurrent requests and the AccountDeleted listener need real commits, so every test removes its own rows. Region
 * ids come from region/002-region-seed.csv (9001 is the abolished test region); libraries are the enabled seed ones.
 */
@SpringBootTest(properties = { "chaekcheck.auth.enabled=true",
		"chaekcheck.auth.public-base-url=http://localhost:5173", "chaekcheck.auth.kakao.admin-key=test-kakao-admin-key", "chaekcheck.auth.kakao.app-id=123456",
		"chaekcheck.auth.naver.client-id=test-naver-id", "chaekcheck.auth.naver.client-secret=test-naver-secret",
		"chaekcheck.auth.kakao.client-id=test-kakao-id", "chaekcheck.auth.kakao.client-secret=test-kakao-secret" })
@AutoConfigureMockMvc
class MyLibraryApiTests {

	static final byte[] KEY = randomBytes(32);

	static final String CSRF = "test-csrf-token";

	static final long SEOUL = 11, JUNG_SEOUL = 1102, MAPO = 1114, TEST_ABOLISHED = 9001;

	@DynamicPropertySource
	static void jwtKey(DynamicPropertyRegistry registry) {
		registry.add("chaekcheck.auth.jwt.secret", () -> Base64.getEncoder().encodeToString(KEY));
	}

	@Autowired
	MockMvcTester mvc;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	MyLibraryService service;

	@Autowired
	AccountDeletion deletion;

	@Autowired
	TransactionTemplate transactions;

	@Autowired
	ApplicationEventPublisher events;

	private final List<Long> accounts = new ArrayList<>();

	private Long disabledLibrary;

	/** 21 enabled seed libraries, in id order. */
	private List<Long> enabled;

	@BeforeEach
	void setUp() {
		enabled = jdbc.sql("SELECT id FROM library.library WHERE enabled ORDER BY id LIMIT 21").query(Long.class).list();
		assertThat(enabled).hasSize(21);
	}

	/** Own threads: the common pool may have a single worker on a small machine, which would run them one by one. */
	private final ExecutorService threads = Executors.newFixedThreadPool(2);

	@AfterEach
	void cleanUp() {
		threads.shutdownNow();
		// a deletion's listeners run after its commit; let them finish before the rows are removed here
		await().atMost(Duration.ofSeconds(10))
			.untilAsserted(() -> accounts.forEach(account -> assertThat(incompleteRegistryRows(account)).isZero()));
		for (long account : accounts) {
			jdbc.sql("DELETE FROM auth.account WHERE id = ?").param(account).update();
			jdbc.sql("DELETE FROM mylibrary.account_region WHERE account_id = ?").param(account).update();
			jdbc.sql("DELETE FROM mylibrary.account_library WHERE account_id = ?").param(account).update();
			jdbc.sql("DELETE FROM modulith.event_publication WHERE serialized_event LIKE ?")
				.param("%\"value\":" + account + "}%")
				.update();
		}
		if (disabledLibrary != null) {
			jdbc.sql("DELETE FROM library.library WHERE id = ?").param(disabledLibrary).update();
		}
	}

	@Test
	void schemaChecks() {
		long account = newAccount();
		for (String values : List.of("(?, 'HOME', 1114, 0)", "(?, 'WORK', 0, 0)", "(?, 'WORK', 1114, 20)")) {
			assertThatThrownBy(() -> jdbc.sql(
					"INSERT INTO mylibrary.account_region (account_id, kind, region_id, position) VALUES " + values)
				.param(account)
				.update()).hasMessageContaining("account_region_");
		}
		assertThatThrownBy(() -> jdbc.sql("INSERT INTO mylibrary.account_library (account_id, library_id) VALUES (?, 0)")
			.param(account)
			.update()).hasMessageContaining("account_library_library_id_check");
	}

	@Test
	void savedRegionsComeBackInTheChosenOrder() { // AC-1 server side
		long account = newAccount();

		assertThat(change(mvc.put().uri("/api/v1/me/regions"), account)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"residenceRegionIds\":[1114,1102,1114],\"workRegionIds\":[]}")).hasStatusOk()
			.bodyJson()
			.isStrictlyEqualTo("{\"residenceRegionIds\":[1114,1102],\"workRegionIds\":[]}");
		assertThat(signedIn(mvc.get().uri("/api/v1/me/regions"), account)).hasStatusOk()
			.bodyJson()
			.isStrictlyEqualTo("{\"residenceRegionIds\":[1114,1102],\"workRegionIds\":[]}");

		putRegions(account, "{\"residenceRegionIds\":[],\"workRegionIds\":[1102]}");
		assertThat(signedIn(mvc.get().uri("/api/v1/me/regions"), account)).bodyJson()
			.isStrictlyEqualTo("{\"residenceRegionIds\":[],\"workRegionIds\":[1102]}");
	}

	@Test
	void regionBodyErrors() {
		long account = newAccount();

		assertThat(putRegions(account, "{\"residenceRegionIds\":[1114]}")).hasStatus(HttpStatus.BAD_REQUEST)
			.bodyJson()
			.isLenientlyEqualTo("""
					{"code":"validation_error","errors":[{"field":"workRegionIds","code":"required"}]}""");
		assertThat(putRegions(account, "{\"residenceRegionIds\":[\"a\"],\"workRegionIds\":[0]}")).bodyJson()
			.isLenientlyEqualTo("""
					{"errors":[{"field":"residenceRegionIds","code":"invalid_format"},
					           {"field":"workRegionIds","code":"invalid_format"}]}""");
		assertThat(putRegions(account, "{\"residenceRegionIds\":1114,\"workRegionIds\":[1.5]}")).bodyJson()
			.isLenientlyEqualTo("""
					{"errors":[{"field":"residenceRegionIds","code":"invalid_format"},
					           {"field":"workRegionIds","code":"invalid_format"}]}""");
		String many = LongStream.rangeClosed(1, 21).mapToObj(Long::toString).collect(Collectors.joining(","));
		assertThat(putRegions(account, "{\"residenceRegionIds\":[" + many + "],\"workRegionIds\":[]}")).bodyJson()
			.isLenientlyEqualTo("""
					{"errors":[{"field":"residenceRegionIds","code":"too_many"}]}""");
		assertThat(putRegions(account, "{\"residenceRegionIds\":[11],\"workRegionIds\":[9001,99999]}"))
			.hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
			.bodyJson()
			.isLenientlyEqualTo("""
					{"code":"invalid_region","errors":[
					  {"field":"residenceRegionIds","value":11,"code":"not_sigungu"},
					  {"field":"workRegionIds","value":9001,"code":"abolished"},
					  {"field":"workRegionIds","value":99999,"code":"not_found"}]}""");
		assertThat(regionRows(account)).isZero();
	}

	@Test
	void addAndRemoveLibraries() { // AC-5 server side
		long account = newAccount();
		long first = enabled.get(0), second = enabled.get(1);

		assertThat(signedIn(mvc.get().uri("/api/v1/me/libraries"), account)).hasStatusOk()
			.bodyJson()
			.isStrictlyEqualTo("{\"libraryIds\":[],\"max\":20}");
		assertThat(addLibrary(account, second)).hasStatus(HttpStatus.NO_CONTENT);
		assertThat(addLibrary(account, first)).hasStatus(HttpStatus.NO_CONTENT);
		assertThat(addLibrary(account, second)).hasStatus(HttpStatus.NO_CONTENT);
		assertThat(signedIn(mvc.get().uri("/api/v1/me/libraries"), account)).bodyJson()
			.isStrictlyEqualTo("{\"libraryIds\":[%d,%d],\"max\":20}".formatted(second, first));

		assertThat(change(mvc.delete().uri("/api/v1/me/libraries/" + second), account))
			.hasStatus(HttpStatus.NO_CONTENT);
		assertThat(change(mvc.delete().uri("/api/v1/me/libraries/" + second), account))
			.hasStatus(HttpStatus.NO_CONTENT);
		assertThat(signedIn(mvc.get().uri("/api/v1/me/libraries"), account)).bodyJson()
			.isStrictlyEqualTo("{\"libraryIds\":[%d],\"max\":20}".formatted(first));
	}

	@Test
	void unknownLibraryOrPathIs404() {
		long account = newAccount();

		for (String path : List.of("999999999", "abc", "0", "-1")) {
			assertThat(change(mvc.put().uri("/api/v1/me/libraries/" + path), account))
				.hasStatus(HttpStatus.NOT_FOUND)
				.bodyJson()
				.extractingPath("$.code")
				.isEqualTo("library_not_found");
		}
		assertThat(libraryRows(account)).isZero();
	}

	@Test
	void twentyFirstLibraryIsRefusedButAnExistingOneStillPasses() { // AC-8
		long account = newAccount();
		enabled.subList(0, 19).forEach(id -> insertLibrary(account, id));

		assertThat(addLibrary(account, enabled.get(19))).hasStatus(HttpStatus.NO_CONTENT);
		assertThat(addLibrary(account, enabled.get(20))).hasStatus(HttpStatus.CONFLICT)
			.bodyJson()
			.isLenientlyEqualTo("""
					{"status":409,"code":"library_limit_reached","detail":"At most 20 libraries can be added"}""");
		assertThat(addLibrary(account, enabled.get(0))).hasStatus(HttpStatus.NO_CONTENT);
		assertThat(libraryRows(account)).isEqualTo(20);
	}

	@Test
	void disabledLibraryCannotBeAddedButASavedOneStaysAndCounts() { // AC-10
		long account = newAccount();
		disabledLibrary = jdbc.sql("""
				INSERT INTO library.library (name, platform_type, base_url, join_method, enabled)
				VALUES ('테스트 꺼짐', 'KYOBO_V1', 'https://disabled.mylibrary.test', 'test', false) RETURNING id""")
			.query(Long.class)
			.single();

		assertThat(addLibrary(account, disabledLibrary)).hasStatus(HttpStatus.NOT_FOUND);

		insertLibrary(account, disabledLibrary);
		enabled.subList(0, 19).forEach(id -> insertLibrary(account, id));
		assertThat(signedIn(mvc.get().uri("/api/v1/me/libraries"), account)).bodyJson()
			.extractingPath("$.libraryIds")
			.asArray()
			.hasSize(20)
			.contains(disabledLibrary.intValue());
		assertThat(addLibrary(account, enabled.get(19))).hasStatus(HttpStatus.CONFLICT);
	}

	@Test
	void emptyingEverythingLeavesNoRow() { // AC-13
		long account = newAccount();
		putRegions(account, "{\"residenceRegionIds\":[1114],\"workRegionIds\":[1102]}");
		addLibrary(account, enabled.get(0));

		putRegions(account, "{\"residenceRegionIds\":[],\"workRegionIds\":[]}");
		change(mvc.delete().uri("/api/v1/me/libraries/" + enabled.get(0)), account).exchange();

		assertThat(regionRows(account)).isZero();
		assertThat(libraryRows(account)).isZero();
	}

	@Test
	void changesNeedTheCsrfTokenAndEverythingNeedsTheLogin() {
		long account = newAccount();

		for (MockMvcRequestBuilder request : List.of(mvc.put().uri("/api/v1/me/libraries/2"),
				mvc.delete().uri("/api/v1/me/libraries/2"), mvc.put()
					.uri("/api/v1/me/regions")
					.contentType(MediaType.APPLICATION_JSON)
					.content("{\"residenceRegionIds\":[],\"workRegionIds\":[]}"))) {
			assertThat(request.cookie(new Cookie("access_token", token(account)))).hasStatus(HttpStatus.FORBIDDEN)
				.bodyJson()
				.extractingPath("$.code")
				.isEqualTo("csrf_invalid");
		}
		for (String path : List.of("/api/v1/me/regions", "/api/v1/me/libraries")) {
			assertThat(mvc.get().uri(path)).hasStatus(HttpStatus.UNAUTHORIZED)
				.bodyJson()
				.extractingPath("$.code")
				.isEqualTo("unauthorized");
		}
	}

	@Test
	void deletedAccountWritesNothing() { // AC-12
		long account = newAccount();
		String token = token(account);
		deletion.delete(account);

		assertThat(mvc.put()
			.uri("/api/v1/me/libraries/" + enabled.get(0))
			.cookie(new Cookie("access_token", token), new Cookie("XSRF-TOKEN", CSRF))
			.header("X-XSRF-TOKEN", CSRF)).hasStatus(HttpStatus.UNAUTHORIZED);
		// past the token check (deleted in between): the lock finds no account
		assertThatThrownBy(() -> service.addLibrary(new AccountId(account), enabled.get(0)))
			.isInstanceOf(MyLibraryService.AccountGoneException.class);
		assertThatThrownBy(() -> service.saveRegions(new AccountId(account), List.of(MAPO), List.of()))
			.isInstanceOf(MyLibraryService.AccountGoneException.class);
		assertThat(libraryRows(account)).isZero();
		assertThat(regionRows(account)).isZero();
	}

	@Test
	void twoAddsAtNineteenGiveExactlyTwenty() throws Exception { // AC-8, concurrency 1
		for (int round = 0; round < 5; round++) {
			long account = newAccount();
			enabled.subList(0, 19).forEach(id -> insertLibrary(account, id));
			CountDownLatch start = new CountDownLatch(1);

			List<CompletableFuture<String>> adds = List.of(enabled.get(19), enabled.get(20))
				.stream()
				.map(id -> CompletableFuture.supplyAsync(() -> {
					awaitLatch(start);
					try {
						service.addLibrary(new AccountId(account), id);
						return "added";
					}
					catch (MyLibraryService.LibraryLimitException ex) {
						return "limit";
					}
				}, threads))
				.toList();
			start.countDown();

			List<String> outcomes = new ArrayList<>();
			for (CompletableFuture<String> add : adds) {
				outcomes.add(add.get(10, TimeUnit.SECONDS));
			}
			assertThat(outcomes).as("round " + round).containsExactlyInAnyOrder("added", "limit");
			assertThat(libraryRows(account)).isEqualTo(20);
		}
	}

	@Test
	void deletionDuringASaveWaitsAndThenTheListenerClearsTheRows() throws Exception { // concurrency 2 (a)
		long account = newAccount();
		CountDownLatch saved = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);

		CompletableFuture<Void> saving = CompletableFuture.runAsync(() -> transactions.executeWithoutResult(status -> {
			service.addLibrary(new AccountId(account), enabled.get(0));
			service.saveRegions(new AccountId(account), List.of(MAPO), List.of(JUNG_SEOUL));
			saved.countDown();
			awaitLatch(release);
		}), threads);
		assertThat(saved.await(10, TimeUnit.SECONDS)).isTrue();
		CompletableFuture<Boolean> deleting = CompletableFuture.supplyAsync(() -> deletion.delete(account), threads);
		awaitDeletionBlockedOnALock();
		assertThat(deleting).as("deletion waits for the save").isNotDone();

		release.countDown();
		saving.get(10, TimeUnit.SECONDS);
		assertThat(deleting.get(10, TimeUnit.SECONDS)).isTrue();
		await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
			assertThat(libraryRows(account)).isZero();
			assertThat(regionRows(account)).isZero();
			assertThat(registryRows(account)).isZero();
		});
	}

	@Test
	void saveAfterTheDeletionCommittedIsRefused() { // concurrency 2 (b)
		long account = newAccount();
		deletion.delete(account);

		assertThatThrownBy(() -> transactions.executeWithoutResult(
				status -> service.addLibrary(new AccountId(account), enabled.get(0))))
			.isInstanceOf(MyLibraryService.AccountGoneException.class);
		assertThat(libraryRows(account)).isZero();
	}

	@Test
	void withdrawalClearsBothTablesAndTheEventCanComeTwice() { // AC-11
		long account = newAccount();
		putRegions(account, "{\"residenceRegionIds\":[1114],\"workRegionIds\":[1102]}");
		addLibrary(account, enabled.get(0));

		deletion.delete(account);

		await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
			assertThat(regionRows(account)).isZero();
			assertThat(libraryRows(account)).isZero();
			assertThat(registryRows(account)).isZero();
		});
		// the same event again (a republished one): no error, and its record completes too
		transactions.executeWithoutResult(status -> events.publishEvent(new AccountDeleted(new AccountId(account))));
		await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(registryRows(account)).isZero());
		assertThat(regionRows(account)).isZero();
	}

	/** Another session runs the account DELETE and waits for a lock (pg_stat_activity), so it is not merely slow. */
	private void awaitDeletionBlockedOnALock() {
		await().atMost(Duration.ofSeconds(10))
			.untilAsserted(() -> assertThat(jdbc.sql("""
					SELECT count(*) FROM pg_stat_activity
					WHERE wait_event_type = 'Lock' AND query LIKE 'DELETE FROM auth.account%'""")
				.query(Long.class)
				.single()).isPositive());
	}

	private long incompleteRegistryRows(long account) {
		return jdbc.sql("""
				SELECT count(*) FROM modulith.event_publication
				WHERE serialized_event LIKE ? AND completion_date IS NULL""")
			.param("%\"value\":" + account + "}%")
			.query(Long.class)
			.single();
	}

	private MvcTestResult putRegions(long account, String body) {
		return change(mvc.put().uri("/api/v1/me/regions"), account).contentType(MediaType.APPLICATION_JSON)
			.content(body)
			.exchange();
	}

	private MvcTestResult addLibrary(long account, long libraryId) {
		return change(mvc.put().uri("/api/v1/me/libraries/" + libraryId), account).exchange();
	}

	private MockMvcRequestBuilder signedIn(MockMvcRequestBuilder request, long account) {
		return request.cookie(new Cookie("access_token", token(account)));
	}

	private MockMvcRequestBuilder change(MockMvcRequestBuilder request, long account) {
		return request.cookie(new Cookie("access_token", token(account)), new Cookie("XSRF-TOKEN", CSRF))
			.header("X-XSRF-TOKEN", CSRF);
	}

	private long newAccount() {
		long id = jdbc.sql("INSERT INTO auth.account (last_login_at) VALUES (now()) RETURNING id")
			.query(Long.class)
			.single();
		accounts.add(id);
		return id;
	}

	private void insertLibrary(long account, long libraryId) {
		jdbc.sql("INSERT INTO mylibrary.account_library (account_id, library_id) VALUES (?, ?)")
			.params(account, libraryId)
			.update();
	}

	private long regionRows(long account) {
		return jdbc.sql("SELECT count(*) FROM mylibrary.account_region WHERE account_id = ?")
			.param(account)
			.query(Long.class)
			.single();
	}

	private long libraryRows(long account) {
		return jdbc.sql("SELECT count(*) FROM mylibrary.account_library WHERE account_id = ?")
			.param(account)
			.query(Long.class)
			.single();
	}

	private long registryRows(long account) {
		return jdbc.sql("SELECT count(*) FROM modulith.event_publication WHERE serialized_event LIKE ?")
			.param("%\"value\":" + account + "}%")
			.query(Long.class)
			.single();
	}

	private static String token(long account) {
		try {
			SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256),
					new JWTClaimsSet.Builder().subject(String.valueOf(account))
						.expirationTime(Date.from(Instant.now().plus(15, ChronoUnit.MINUTES)))
						.build());
			jwt.sign(new MACSigner(KEY));
			return jwt.serialize();
		}
		catch (JOSEException ex) {
			throw new IllegalStateException(ex);
		}
	}

	private static void awaitLatch(CountDownLatch latch) {
		try {
			if (!latch.await(10, TimeUnit.SECONDS)) {
				throw new IllegalStateException("Latch not released");
			}
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(ex);
		}
	}

	private static byte[] randomBytes(int size) {
		byte[] bytes = new byte[size];
		new SecureRandom().nextBytes(bytes);
		return bytes;
	}

}
