package com.chaekcheck.collection.internal.libraryinsert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.chaekcheck.collection.internal.libraryinsert.CollectedLibraries.CollectedLibrary;
import com.chaekcheck.collection.internal.libraryinsert.InsertPlan.NewEntry;
import com.chaekcheck.collection.internal.libraryinsert.InsertPlan.Suspect;
import com.chaekcheck.library.JoinSurvey;
import com.chaekcheck.library.LendingModel;
import com.chaekcheck.library.NewEligibilityRule;
import com.chaekcheck.library.NewLibrary;
import com.chaekcheck.library.PlatformType;
import com.chaekcheck.library.RegisteredLibrary;
import com.chaekcheck.library.Vendor;

/**
 * Plan cycle 13 handoff step 5: the rules of the old seed script, ported (R2-4 to R2-7), without a database. A copy
 * here is a list of {@link CollectedLibrary}; shared item keys are a map.
 */
class LibraryInsertPlannerTests {

	static final String DONE = "2026-10-01T00:00:00+09:00";

	@TempDir
	Path dir;

	RulesFolder folder = new RulesFolder();

	List<CollectedLibrary> collected = new ArrayList<>();

	List<RegisteredLibrary> service = new ArrayList<>();

	Map<Long, Long> links = new HashMap<>();

	/** "a b" -> shared item keys of collection ids a and b (either order). */
	Map<String, Long> shared = new HashMap<>();

	/** Collection id -> rows the import would take; 1 when not set (cycle 18 "no e-books" check). */
	Map<Long, Long> importable = new HashMap<>();

	/** "id host" of every importable-rows question. */
	List<String> importableAsked = new ArrayList<>();

	@Test
	void eachRuleLeavesOneLibraryOutInOrder() {
		String rentalUrl = "https://rental.dkyobobook.co.kr";
		folder.row("kyobo_probe.tsv", rentalUrl + "/", "", "", "대여", "RENTAL", "");
		collect(100001, "대여", "KYOBO_SUBSCRIPTION_V1", rentalUrl, DONE, "also skipped"); // the probe wins
		collect(100002, "받지 않음", "YES24_V2", "https://skip.test", DONE, "blocked");
		collect(100003, "실패", "YES24_V2", "https://fail.test", null, null);
		collect(100004, "체험용", "YES24_V2", "https://demo.test", DONE, null);
		folder.row("demo_or_closed.tsv", "100004", "체험용", "2026-10-05", "");
		collect(100005, "같은 사이트", "KYOBO_V1", "https://same.test:8080", DONE, null);
		folder.row("same_site.tsv", "100005", "같은 사이트", "1", "2026-10-05", "");
		collect(100006, "시험", "YES24_V2", "https://test.test", DONE, null);
		folder.kind(100006, "TEST");
		collect(100007, "남는 곳", "YES24_V2", "https://kept.test", DONE, null);
		folder.kind(100007, "UNIV").origin("https://kept.test");

		InsertPlan plan = plan(2675);

		assertThat(plan.leftOut()).containsEntry("kyobo_rental_mixed", List.of(100001L))
			.containsEntry("crawl_skip", List.of(100002L))
			.containsEntry("no_full_success", List.of(100003L))
			.containsEntry("demo_or_closed", List.of(100004L))
			.containsEntry("same_site", List.of(100005L))
			.containsEntry("test", List.of(100006L));
		assertThat(plan.newLibraries()).extracting(NewEntry::collectionId).containsExactly(100007L);
	}

	@Test
	void linkedLibrariesAreNotNew() {
		collect(100007, "있는 곳", "YES24_V2", "https://linked.test", DONE, null);
		inService(2300, "있는 곳", "https://linked.test", PlatformType.YES24_V2, Vendor.YES24, LendingModel.OWNED);
		links.put(100007L, 2300L);

		assertThat(plan(2675).newLibraries()).isEmpty();
	}

	@Test
	void aLibraryWithoutAKindStops() {
		collect(100007, "종류 없음", "YES24_V2", "https://nokind.test", DONE, null);
		folder.origin("https://nokind.test");

		assertRulesError(() -> plan(2675), "no kind");
	}

	@Test
	void anUnknownVendorStops() {
		collected.add(new CollectedLibrary(100007, "업체", "YES24_V2", "https://v.test", DONE, null, "ACME"));
		assertRulesError(() -> plan(2675), "unknown vendor");
	}

	@Test
	void anUnknownKindInTheRulesStops() {
		folder.kind(100007, "SHOP");
		assertRulesError(() -> plan(2675), "unknown kind");
	}

	@Test
	void aNewLibraryNeedsAFinalOrigin() {
		collect(100007, "주소 확인 안 함", "YES24_V2", "http://unchecked.test", DONE, null);
		folder.kind(100007, "UNIV");
		assertRulesError(() -> plan(2675), "final_origins");
	}

	@Test
	void valuesFollowTheOldScript() {
		collect(100010, "한화S&amp;C", "KYOBO_SUBSCRIPTION_V1", "https://hsnc.dkyobobook.co.kr", DONE, null);
		folder.kind(100010, "COMPANY").origin("https://hsnc.dkyobobook.co.kr");
		collect(100011, "교보 소장 구독형", "KYOBO_SUBSCRIPTION_V1", "https://owned.dkyobobook.co.kr", DONE, null);
		folder.kind(100011, "COMPANY").origin("https://owned.dkyobobook.co.kr")
			.row("kyobo_probe.tsv", "https://owned.dkyobobook.co.kr/", "", "", "소장", "OWNED", "");
		collect(100012, "북큐브 구독", "BOOKCUBE_FX", "https://sub.bookcube.biz/FxLibrary", DONE, null);
		folder.kind(100012, "SCHOOL").origin("https://sub.bookcube.biz/FxLibrary")
			.row("bookcube_volume.tsv", "100012", "", "", "120", "0", "", "2026-10-11");
		collect(100013, "북큐브 소장", "BOOKCUBE_FX", "https://own.bookcube.biz/FxLibrary", DONE, null);
		folder.kind(100013, "SCHOOL").origin("https://own.bookcube.biz/FxLibrary")
			.row("bookcube_volume.tsv", "100013", "", "", "120", "130", "", "2026-10-11");
		collect(100014, "포털", "ELIB_PORTAL_V1", "https://portal.test", DONE, null);
		folder.kind(100014, "ORG").origin("https://portal.test");
		collected.add(new CollectedLibrary(100015, "업체 적힘", "YES24_V2", "http://moved.test", DONE, null,
				"KYOBO"));
		folder.kind(100015, "UNKNOWN").origin("http://moved.test", "https://moved.test:444");
		collect(100016, "서울 자체", "SEOUL_ELIB_V1", "https://elib.seoul.test", DONE, null);
		folder.kind(100016, "ORG").origin("https://elib.seoul.test");

		Map<Long, NewLibrary> added = byCollectionId(plan(2675));

		assertThat(added.get(100010L).name()).isEqualTo("한화S&C");
		assertThat(added.get(100010L).lendingModel()).isEqualTo(LendingModel.SUBSCRIPTION); // no probe row
		assertThat(added.get(100011L).lendingModel()).isEqualTo(LendingModel.OWNED);
		assertThat(added.get(100012L).lendingModel()).isEqualTo(LendingModel.SUBSCRIPTION);
		assertThat(added.get(100013L).lendingModel()).isEqualTo(LendingModel.OWNED);
		assertThat(added.get(100014L).lendingModel()).isNull();
		assertThat(added.get(100014L).vendor()).isEqualTo(Vendor.PORTAL);
		assertThat(added.get(100016L)).extracting(NewLibrary::vendor, NewLibrary::lendingModel)
			.containsExactly(Vendor.IN_HOUSE, LendingModel.MIXED); // owned and subscription titles, 4-14
		NewLibrary moved = added.get(100015L);
		assertThat(moved.vendor()).isEqualTo(Vendor.KYOBO);
		assertThat(moved.lendingModel()).isEqualTo(LendingModel.OWNED);
		assertThat(moved.baseUrl()).isEqualTo("https://moved.test:444");
		assertThat(moved.homepageUrl()).isEqualTo("https://moved.test:444");
		assertThat(moved.joinSurvey()).isEqualTo(JoinSurvey.NOT_SURVEYED);
		assertThat(added.get(100012L).joinSurvey()).isEqualTo(JoinSurvey.MEMBERS_ONLY);
		assertThat(added.values()).extracting(NewLibrary::id).containsExactlyInAnyOrder(2675L, 2676L, 2677L, 2678L,
				2679L, 2680L, 2681L);
		assertThat(added.get(100010L).id()).isEqualTo(2675L);
	}

	@Test
	void nameOverridesApplyByServiceId() {
		collect(100020, "옛 이름", "YES24_V2", "https://renamed.test", DONE, null);
		folder.kind(100020, "UNIV").origin("https://renamed.test")
			.row("name_overrides.tsv", "2675", "옛 이름", "새 이름", "2026-10-12", "", "");
		assertThat(byCollectionId(plan(2675)).get(100020L).name()).isEqualTo("새 이름");
	}

	@Test
	void aStaleNameOverrideStops() {
		collect(100020, "다른 이름", "YES24_V2", "https://renamed.test", DONE, null);
		folder.kind(100020, "UNIV").origin("https://renamed.test")
			.row("name_overrides.tsv", "2675", "옛 이름", "새 이름", "2026-10-12", "", "");
		assertRulesError(() -> plan(2675), "name override");
	}

	@Test
	void publicLibrariesTakeTheSurvey() {
		collect(100030, "지역", "YES24_V2", "https://region.test", DONE, null);
		folder.kind(100030, "PUBLIC").origin("https://region.test")
			.publicJoin(100030, "REGION", "RESIDENCE:41;WORK:41", "경기 거주·재직");
		collect(100031, "누구나", "YES24_V2", "https://open.test", DONE, null);
		folder.kind(100031, "PUBLIC").origin("https://open.test").publicJoin(100031, "OPEN", "RESIDENCE:NATIONWIDE", "");
		collect(100032, "못 찾음", "YES24_V2", "https://notfound.test", DONE, null);
		folder.kind(100032, "PUBLIC").origin("https://notfound.test").publicJoin(100032, "UNVERIFIED", "", "");
		collect(100033, "소속", "YES24_V2", "https://members.test", DONE, null);
		folder.kind(100033, "PUBLIC").origin("https://members.test").publicJoin(100033, "AFFILIATION", "", "");

		Map<Long, NewLibrary> added = byCollectionId(plan(2675));

		assertThat(added.get(100030L).joinSurvey()).isEqualTo(JoinSurvey.SURVEYED);
		assertThat(added.get(100030L).eligibilitySummary()).isEqualTo("경기 거주·재직");
		assertThat(added.get(100030L).rules()).containsExactly(new NewEligibilityRule("RESIDENCE", "REGION", 41L),
				new NewEligibilityRule("WORK", "REGION", 41L));
		assertThat(added.get(100031L).rules()).containsExactly(new NewEligibilityRule("RESIDENCE", "NATIONWIDE", null));
		assertThat(added.get(100031L).eligibilitySummary()).isNull();
		assertThat(added.get(100032L).joinSurvey()).isEqualTo(JoinSurvey.NOT_FOUND);
		assertThat(added.get(100032L).rules()).isEmpty();
		assertThat(added.get(100033L).joinSurvey()).isEqualTo(JoinSurvey.MEMBERS_ONLY);
		assertThat(added.get(100033L).joinUrl()).isEqualTo("https://join.test/100033");
	}

	@Test
	void orderNumbersCountTheWholeService() {
		// 김천대: BOOKCUBE in the service (467), the new YES24 site is another (vendor, lending model): order 1
		inService(467, "김천대학교", "https://gimcheon.bookcube.biz/FxLibrary", PlatformType.BOOKCUBE_FX,
				Vendor.BOOKCUBE, LendingModel.OWNED);
		collect(102092, "김천대학교", "YES24_V3", "http://yes24.gimcheon.ac.kr", DONE, null);
		folder.kind(102092, "UNIV").origin("http://yes24.gimcheon.ac.kr");
		// same name, vendor and lending model as a disabled service library: order 2, a suspect
		inService(59, "꺼진 곳", "https://off.test", PlatformType.YES24_V2, Vendor.YES24, LendingModel.OWNED);
		collect(100040, "꺼진 곳", "YES24_V2", "https://off2.test", DONE, null);
		folder.kind(100040, "UNIV").origin("https://off2.test");

		InsertPlan plan = plan(2675);

		assertThat(byCollectionId(plan).get(102092L).orderNo()).isEqualTo(1);
		assertThat(byCollectionId(plan).get(100040L).orderNo()).isEqualTo(2);
		assertThat(plan.suspects()).extracting(Suspect::kind, Suspect::collectionId)
			.containsExactly(org.assertj.core.groups.Tuple.tuple("ORDER_NO", 100040L));
		assertThat(plan.hasOrderNo()).isTrue();
	}

	@Test
	void anAllowedOrderNumberIsNoSuspect() {
		inService(59, "꺼진 곳", "https://off.test", PlatformType.YES24_V2, Vendor.YES24, LendingModel.OWNED);
		collect(100040, "꺼진 곳", "YES24_V2", "https://off2.test", DONE, null);
		folder.kind(100040, "UNIV").origin("https://off2.test").row("order_no_allowed.tsv", "100040", "2", "", "");
		assertThat(plan(2675).suspects()).isEmpty();
	}

	@Test
	void sameHostWithSharedKeysIsASuspect() {
		// new against the service: the port does not count (여주대 8091 / 80)
		collect(100584, "여주대학교", "KYOBO_V1", "http://ebook.yit.ac.kr:8091", DONE, null);
		folder.kind(100584, "UNIV").origin("http://ebook.yit.ac.kr:8091", "https://ebook.yit.ac.kr:446");
		collect(300, "서비스 쪽", "YES24_V2", "http://ebook.yit.ac.kr", DONE, null);
		inService(300, "서비스 쪽", "http://ebook.yit.ac.kr", PlatformType.YES24_V2, Vendor.YES24, LendingModel.OWNED);
		links.put(300L, 300L);
		shared.put("100584 300", 2198L);
		// new against new, with "_" in the host (java.net.URI gives no host there)
		collect(100700, "밑줄 하나", "YES24_V2", "https://e_book.school.test", DONE, null);
		folder.kind(100700, "SCHOOL").origin("https://e_book.school.test");
		collect(100701, "밑줄 둘", "YES24_V2", "https://e_book.school.test:8443/elib", DONE, null);
		folder.kind(100701, "SCHOOL").origin("https://e_book.school.test:8443/elib");
		shared.put("100701 100700", 5L);
		// same host, no shared keys: fine
		collect(100800, "같은 호스트 다른 책", "YES24_V2", "https://other.test", DONE, null);
		folder.kind(100800, "SCHOOL").origin("https://other.test");
		collect(100801, "같은 호스트 다른 책 2", "YES24_V2", "https://other.test:81", DONE, null);
		folder.kind(100801, "SCHOOL").origin("https://other.test:81");

		InsertPlan plan = plan(2675);

		assertThat(plan.suspects()).extracting(Suspect::kind, Suspect::collectionId, Suspect::otherCollectionId,
				Suspect::otherLibraryId)
			.containsExactlyInAnyOrder(org.assertj.core.groups.Tuple.tuple("SAME_HOST", 100584L, 300L, 300L),
					org.assertj.core.groups.Tuple.tuple("SAME_HOST", 100701L, 100700L, null));
		assertThat(plan.hasSameHost()).isTrue();
	}

	@Test
	void keptLibrariesAreNotAskedAgain() {
		collect(100584, "여주대학교", "KYOBO_V1", "http://ebook.yit.ac.kr:8091", DONE, null);
		folder.kind(100584, "UNIV").origin("http://ebook.yit.ac.kr:8091", "https://ebook.yit.ac.kr:446");
		collect(102084, "여주대학교", "YES24_V2", "http://ebook.yit.ac.kr", DONE, null);
		folder.kind(102084, "UNIV").origin("http://ebook.yit.ac.kr");
		shared.put("102084 100584", 2198L);
		folder.row("same_host_kept.tsv", "100584", "여주대학교", "2026-10-12", "")
			.row("same_host_kept.tsv", "102084", "여주대학교", "2026-10-12", "");

		InsertPlan plan = plan(2675);

		assertThat(plan.suspects()).isEmpty();
		assertThat(plan.newLibraries()).hasSize(2);
	}

	@Test
	void changedAndGoneCompareTheLinkedLibraries() {
		// address changed (after the final origin), name changed (after the override and HTML), platform changed
		collect(10, "새 주소", "YES24_V2", "http://moved.test", DONE, null);
		folder.origin("http://moved.test", "https://moved.test");
		inService(10, "새 주소", "https://old.test", PlatformType.YES24_V2, Vendor.YES24, LendingModel.OWNED);
		collect(11, "A&amp;B", "YES24_V2", "https://name.test/", DONE, null);
		inService(11, "A&B", "https://name.test", PlatformType.YES24_V2, Vendor.YES24, LendingModel.OWNED);
		collect(12, "화면", "YES24_V3", "https://screen.test", DONE, null);
		inService(12, "화면", "https://screen.test", PlatformType.YES24_V2, Vendor.YES24, LendingModel.OWNED);
		collect(13, "실패", "YES24_V2", "https://failed.test", null, null);
		inService(13, "실패", "https://failed.test", PlatformType.YES24_V2, Vendor.YES24, LendingModel.OWNED);
		// gone: missing, not crawled, not crawled but checked by a person, and a disabled one (not compared)
		inService(20, "없어짐", "https://missing.test", PlatformType.YES24_V2, Vendor.YES24, LendingModel.OWNED);
		collect(21, "받지 않음", "YES24_V2", "https://skip.test", DONE, "blocked");
		inService(21, "받지 않음", "https://skip.test", PlatformType.YES24_V2, Vendor.YES24, LendingModel.OWNED);
		collect(44, "확인함", "YES24_V2", "https://known.test", DONE, "empty page");
		inService(44, "확인함", "https://known.test", PlatformType.YES24_V2, Vendor.YES24, LendingModel.OWNED);
		folder.row("gone_known.tsv", "44", "확인함", "2026-10-12", "2026-10-12", "");
		service.add(new RegisteredLibrary(59, "꺼짐", "https://off.test", PlatformType.YES24_V2, Vendor.YES24,
				LendingModel.OWNED, 1, false));
		for (long id : List.of(10L, 11L, 12L, 13L, 20L, 21L, 44L, 59L)) {
			links.put(id, id);
		}

		InsertPlan plan = plan(2675);

		assertThat(plan.changed()).extracting(InsertPlan.Change::libraryId, InsertPlan.Change::field)
			.containsExactlyInAnyOrder(org.assertj.core.groups.Tuple.tuple(10L, "base_url"),
					org.assertj.core.groups.Tuple.tuple(12L, "platform_type"),
					org.assertj.core.groups.Tuple.tuple(13L, "crawl"));
		assertThat(plan.gone()).extracting(InsertPlan.Gone::libraryId).containsExactly(20L, 21L);
		assertThat(plan.goneKnown()).containsExactly(44L);
	}

	@Test
	void aLibraryWithoutEbooksIsLeftOutLastAndBeforeTheRulesChecks() { // cycle 18, ADR-0110
		collect(100008, "오디오북만", "BOOKCUBE_FX", "http://audio.test", DONE, null);
		importable.put(100008L, 0L);
		// no kind and no final origin: a library with e-books would stop the run here
		collect(100009, "옮긴 곳", "YES24_V2", "http://old.test", DONE, null);
		folder.kind(100009, "UNIV").origin("http://old.test", "https://new.test:8443");

		InsertPlan plan = plan(2675);

		assertThat(plan.leftOut().keySet()).containsExactly("kyobo_rental_mixed", "crawl_skip", "no_full_success",
				"demo_or_closed", "same_site", "test", "no_ebooks");
		assertThat(plan.leftOut().get("no_ebooks")).containsExactly(100008L);
		assertThat(plan.newLibraries()).extracting(InsertPlan.NewEntry::collectionId).containsExactly(100009L);
		// counted against the host of the address the library would get
		assertThat(importableAsked).contains("100009 new.test", "100008 audio.test");
	}

	@Test
	void hostsAreComparedWithoutSchemeAndPort() {
		assertThat(LibraryInsertPlanner.host("http://ebook.yit.ac.kr:8091")).isEqualTo("ebook.yit.ac.kr");
		assertThat(LibraryInsertPlanner.host("https://EBOOK.yit.ac.kr:446/elibrary-front/")).isEqualTo("ebook.yit.ac.kr");
		assertThat(LibraryInsertPlanner.host("https://e_book.test/x?y")).isEqualTo("e_book.test");
	}

	@Test
	void theSameInputGivesTheSameReportAndARulesFileChangesIt() throws Exception {
		collect(100007, "곳", "YES24_V2", "https://a.test", DONE, null);
		folder.kind(100007, "UNIV").origin("https://a.test");
		RuleSet rules = folder.read(dir);
		InsertPlan plan = planner(rules);
		String id = InsertReport.of(plan, rules, "copy.sqlite").id();

		assertThat(InsertReport.of(planner(RuleSet.read(dir)), RuleSet.read(dir), "copy.sqlite").id()).isEqualTo(id);
		// a line that changes no library still changes the report (AC-5)
		Files.writeString(dir.resolve("demo_or_closed.tsv"), "\n999999\t없는 곳\t2026-10-12\t\n",
				java.nio.file.StandardOpenOption.APPEND);
		RuleSet changed = RuleSet.read(dir);
		assertThat(InsertReport.of(planner(changed), changed, "copy.sqlite").id()).isNotEqualTo(id);
		assertThat(InsertReport.of(plan, rules, "other-copy.sqlite").id()).isNotEqualTo(id);
	}

	@Test
	void lineEndsDoNotChangeTheReport() throws Exception {
		folder.kind(100007, "UNIV");
		RuleSet unix = folder.read(dir);
		Path windows = dir.resolve("crlf");
		Files.createDirectories(windows);
		try (var files = Files.list(dir)) {
			for (Path file : files.filter(Files::isRegularFile).toList()) {
				Files.writeString(windows.resolve(file.getFileName()), Files.readString(file).replace("\n", "\r\n"));
			}
		}
		assertThat(RuleSet.read(windows).fileHashes()).isEqualTo(unix.fileHashes());
	}

	private InsertPlan plan(long nextId) {
		return planner(folder.read(dir), nextId);
	}

	private InsertPlan planner(RuleSet rules) {
		return planner(rules, 2675);
	}

	private InsertPlan planner(RuleSet rules, long nextId) {
		return new LibraryInsertPlanner(rules).plan(collected, service, links, nextId, new LibraryInsertPlanner.Holdings() {

			@Override
			public long keys(long collectionId) {
				return 100;
			}

			@Override
			public long shared(long collectionId, long otherCollectionId) {
				return LibraryInsertPlannerTests.this.shared.getOrDefault(collectionId + " " + otherCollectionId,
						LibraryInsertPlannerTests.this.shared.getOrDefault(otherCollectionId + " " + collectionId, 0L));
			}

			@Override
			public long importableRows(long collectionId, String libraryHost) {
				importableAsked.add(collectionId + " " + libraryHost);
				return importable.getOrDefault(collectionId, 1L);
			}

		});
	}

	private void collect(long id, String name, String platform, String baseUrl, String fullSuccess, String skip) {
		collected.add(new CollectedLibrary(id, name, platform, baseUrl, fullSuccess, skip, null));
	}

	private void inService(long id, String name, String baseUrl, PlatformType platform, Vendor vendor,
			LendingModel lending) {
		service.add(new RegisteredLibrary(id, name, baseUrl, platform, vendor, lending, 1, true));
	}

	private static Map<Long, NewLibrary> byCollectionId(InsertPlan plan) {
		Map<Long, NewLibrary> out = new HashMap<>();
		plan.newLibraries().forEach(entry -> out.put(entry.collectionId(), entry.library()));
		return out;
	}

	private static void assertRulesError(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String text) {
		assertThatThrownBy(call).isInstanceOf(LibraryInsertException.class)
			.hasMessageContaining(text)
			.satisfies(ex -> assertThat(((LibraryInsertException) ex).exitCode()).isEqualTo(1));
	}

}
