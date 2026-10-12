package com.chaekcheck.picks.internal.fetch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/** The list answer read and kept (plan cycle 14 step 3; C6, AC-4b, AC-17). */
class SaseoXmlParserTests {

	static final String XML = NlkFixtures.saseo2y();

	@Test
	void savedAnswerGives108ItemsInTwelveMonthsAllKept() {
		SaseoXmlParser.SaseoList list = SaseoXmlParser.parse(NlkFixtures.bytes(XML));

		assertThat(list.totalCount()).isEqualTo(108);
		assertThat(list.rows()).hasSize(108);
		PickRow first = list.rows().getFirst();
		assertThat(first.recomNo()).isEqualTo("20260922163135829100");
		assertThat(first.drCode()).isEqualTo("11");
		assertThat(first.drCodeName()).isEqualTo("어문학");
		assertThat(first.recomMonth()).isEqualTo(LocalDate.of(2026, 10, 1));
		assertThat(first.registeredOn()).isEqualTo(LocalDate.of(2026, 9, 22));
		assertThat(first.title()).isEqualTo("삼문동 봉주르 아파트 : 오서 장편소설");
		assertThat(first.author()).isEqualTo("지은이: 오서");
		assertThat(first.publisher()).isEqualTo("자음과모음");
		assertThat(first.isbn13()).matches("97[89][0-9]{10}");
		assertThat(first.coverSource()).startsWith("https://www.nl.go.kr/afile/fileDownload/");

		KeptPicks kept = KeptPicks.of(list.rows());
		assertThat(kept.rows()).hasSize(108);
		assertThat(kept.receivedCount()).isEqualTo(108);
		assertThat(kept.rows().stream().map(PickRow::recomMonth).distinct()).hasSize(12);
		assertThat(kept.rows()).extracting(PickRow::recomMonth)
			.contains(LocalDate.of(2026, 10, 1), LocalDate.of(2024, 12, 1));
		assertThat(kept.newCategoryCodes()).isNull();
		// the theme field has no name in the API
		assertThat(kept.rows()).filteredOn(row -> row.drCode().equals("425")).extracting(PickRow::drCodeName).containsOnlyNulls();
	}

	@Test
	void thirteenMonthsKeepTheLatestTwelve() { // AC-17 (12 by user decision 2026-10-12)
		List<String> items = new ArrayList<>(NlkFixtures.items(XML));
		String older = NlkFixtures.set(NlkFixtures.set(NlkFixtures.set(items.getLast(), "recomYear", "2024"), "recomMonth",
				"10"), "recomNo", "1");
		items.add(older);
		List<PickRow> rows = SaseoXmlParser.parse(NlkFixtures.bytes(NlkFixtures.withItems(items))).rows();

		KeptPicks kept = KeptPicks.of(rows);

		assertThat(rows.stream().map(PickRow::recomMonth).distinct()).hasSize(13);
		assertThat(kept.rows().stream().map(PickRow::recomMonth).distinct()).hasSize(12);
		assertThat(kept.rows()).hasSize(108).extracting(PickRow::recomNo).doesNotContain("1");
	}

	@Test
	void fewerThanTwelveMonthsKeepsAll() {
		List<String> items = NlkFixtures.items(XML).subList(0, 10);

		KeptPicks kept = KeptPicks.of(SaseoXmlParser.parse(NlkFixtures.bytes(NlkFixtures.withItems(items))).rows());

		assertThat(kept.rows()).hasSize(10);
	}

	@Test
	void noItemsIsNoBooks() {
		SaseoXmlParser.SaseoList list = SaseoXmlParser.parse(NlkFixtures.bytes(NlkFixtures.withItems(List.of())));

		assertThat(list.rows()).isEmpty();
		assertThatThrownBy(() -> KeptPicks.of(list.rows())).isInstanceOf(FetchFailure.class)
			.extracting(ex -> ((FetchFailure) ex).reason())
			.isEqualTo(FailureReason.NO_BOOKS);
	}

	@Test
	void totalCountOverItemsIsIncomplete() {
		List<String> items = NlkFixtures.items(XML);

		assertFailure(NlkFixtures.withItems(items.subList(0, 100), 108), FailureReason.INCOMPLETE,
				"totalCount 108, items 100");
	}

	@Test
	void missingChannelOrTotalCountIsFormatChanged() {
		assertFailure("<?xml version=\"1.0\"?><rss><list></list></rss>", FailureReason.FORMAT_CHANGED, "no channel");
		assertFailure("<?xml version=\"1.0\"?><channel><list></list></channel>", FailureReason.FORMAT_CHANGED,
				"no totalCount");
		assertFailure("not xml", FailureReason.FORMAT_CHANGED, "not readable XML");
	}

	@Test
	void anErrorElementInsteadOfAListIsRequestRejected() { // the real error answer is not known yet [확인 필요]
		assertFailure("<?xml version=\"1.0\"?><error><error_code>010</error_code><msg>잘못된 키 abc</msg></error>",
				FailureReason.REQUEST_REJECTED, "API error 010");
	}

	@Test
	void unknownFieldCodeIsKeptAndNamed() { // AC-4b
		List<String> items = new ArrayList<>(NlkFixtures.items(XML).subList(0, 5));
		items.add(NlkFixtures.set(NlkFixtures.set(items.get(1), "drCode", "999"), "recomNo", "1"));
		items.add(NlkFixtures.set(NlkFixtures.set(NlkFixtures.set(items.get(2), "drCode", "1000"), "drCodeName", ""),
				"recomNo", "2"));
		items.add(NlkFixtures.set(NlkFixtures.set(items.get(3), "drCode", "A7"), "recomNo", "3"));

		KeptPicks kept = KeptPicks.of(SaseoXmlParser.parse(NlkFixtures.bytes(NlkFixtures.withItems(items))).rows());

		assertThat(kept.rows()).hasSize(8);
		assertThat(kept.newCategoryCodes()).isEqualTo("999,1000,A7");
		assertThat(kept.rows()).filteredOn(row -> row.recomNo().equals("2")).extracting(PickRow::drCodeName).containsOnlyNulls();
	}

	@Test
	void isbnsAreCleanedOrDropped() {
		List<String> items = NlkFixtures.items(XML);
		String hyphen = NlkFixtures.set(items.get(0), "recomisbn", "979-11-245-9105-5");
		String ten = NlkFixtures.set(NlkFixtures.set(items.get(1), "recomisbn", "89-374-2851-2"), "recomNo", "11");
		String empty = NlkFixtures.set(NlkFixtures.set(items.get(2), "recomisbn", ""), "recomNo", "12");
		String bad = NlkFixtures.set(NlkFixtures.set(items.get(3), "recomisbn", "1234"), "recomNo", "13");

		KeptPicks kept = KeptPicks.of(SaseoXmlParser
			.parse(NlkFixtures.bytes(NlkFixtures.withItems(List.of(hyphen, ten, empty, bad))))
			.rows());

		assertThat(kept.rows()).extracting(PickRow::isbn13).containsExactly("9791124591055", "9788937428517", null, null);
		assertThat(kept.missingIsbnCount()).isEqualTo(2);
	}

	@Test
	void sameMonthAndDayItemsBothStay() {
		List<String> items = NlkFixtures.items(XML);
		String twin = NlkFixtures.set(items.get(0), "recomNo", "20260922163135829099");

		List<PickRow> rows = SaseoXmlParser.parse(NlkFixtures.bytes(NlkFixtures.withItems(List.of(items.get(0), twin))))
			.rows();

		assertThat(rows).extracting(PickRow::registeredOn).containsOnly(LocalDate.of(2026, 9, 22));
		assertThat(rows).hasSize(2);
	}

	@Test
	void badValuesFailTheList() {
		String item = NlkFixtures.items(XML).getFirst();

		assertFailure(NlkFixtures.withItems(List.of(NlkFixtures.set(item, "recomNo", "123456789012345678901"))),
				FailureReason.FORMAT_CHANGED, "item 1: recomNo");
		assertFailure(NlkFixtures.withItems(List.of(NlkFixtures.set(item, "recomNo", "20a"))),
				FailureReason.FORMAT_CHANGED, "item 1: recomNo");
		assertFailure(NlkFixtures.withItems(List.of(NlkFixtures.set(item, "recomtitle", " "))),
				FailureReason.FORMAT_CHANGED, "item 1: recomtitle");
		assertFailure(NlkFixtures.withItems(List.of(NlkFixtures.set(item, "recomMonth", "13"))),
				FailureReason.FORMAT_CHANGED, "item 1: recomYear/recomMonth");
		assertFailure(NlkFixtures.withItems(List.of(NlkFixtures.set(item, "drCode", ""))),
				FailureReason.FORMAT_CHANGED, "item 1: drCode");
	}

	@Test
	void unreadableRegdateIsNull() {
		String item = NlkFixtures.set(NlkFixtures.items(XML).getFirst(), "regdate", "2026년 10월");

		assertThat(SaseoXmlParser.parse(NlkFixtures.bytes(NlkFixtures.withItems(List.of(item)))).rows().getFirst()
			.registeredOn()).isNull();
	}

	@Test
	void coverAddressesAreCheckedAndMadeHttps() {
		assertThat(CoverUrls.normalize("http://www.nl.go.kr/afile/fileDownload/exWmn"))
			.contains("https://www.nl.go.kr/afile/fileDownload/exWmn");
		assertThat(CoverUrls.normalize("https://www.nl.go.kr/afile/fileDownload/exWmn?x=1"))
			.contains("https://www.nl.go.kr/afile/fileDownload/exWmn?x=1");
		for (String refused : List.of("http://nl.go.kr/afile/fileDownload/a", "http://www.nl.go.kr.evil.com/afile/a",
				"http://www.nl.go.kr/files/a", "http://www.nl.go.kr:8080/afile/a", "http://user@www.nl.go.kr/afile/a",
				"ftp://www.nl.go.kr/afile/a", "http://www.nl.go.kr/afile/../admin", "http://www.nl.go.kr/afile/%2e%2e/a",
				"http://mokcha.nl.go.kr/kolis/2026/a.jpg", "http://www.nl.go.kr/afile/a#x", "", "아무 글")) {
			assertThat(CoverUrls.normalize(refused)).as(refused).isEmpty();
		}
		String item = NlkFixtures.set(NlkFixtures.items(XML).getFirst(), "recomfilepath", "http://www.nl.go.kr/files/a");
		assertThat(SaseoXmlParser.parse(NlkFixtures.bytes(NlkFixtures.withItems(List.of(item)))).rows().getFirst()
			.coverSource()).isNull();
	}

	@Test
	void externalEntitiesAreNotResolved() { // C6, XXE
		String xxe = """
				<?xml version="1.0" encoding="UTF-8"?>
				<!DOCTYPE channel [<!ENTITY xxe SYSTEM "file:///C:/Windows/win.ini">]>
				<channel><totalCount>1</totalCount><list><item><recomNo>1</recomNo><drCode>11</drCode>
				<recomtitle>&xxe;</recomtitle><recomYear>2026</recomYear><recomMonth>10</recomMonth></item></list></channel>
				""";

		assertFailure(xxe, FailureReason.FORMAT_CHANGED, "not readable XML");
	}

	@Test
	void htmlOfContentsIsNeverKept() {
		PickRow row = SaseoXmlParser.parse(NlkFixtures.bytes(XML)).rows().getFirst();

		assertThat(row.toString()).doesNotContain("recommokcha").doesNotContain("recomcontens").doesNotContain("<p>");
	}

	private static void assertFailure(String xml, FailureReason reason, String detail) {
		assertThatThrownBy(() -> SaseoXmlParser.parse(NlkFixtures.bytes(xml))).isInstanceOf(FetchFailure.class)
			.hasMessage(detail)
			.extracting(ex -> ((FetchFailure) ex).reason())
			.isEqualTo(reason);
	}

}
