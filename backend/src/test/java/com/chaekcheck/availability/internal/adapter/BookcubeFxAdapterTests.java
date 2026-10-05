package com.chaekcheck.availability.internal.adapter;

import static com.chaekcheck.availability.internal.adapter.AdapterFixtures.bytes;
import static com.chaekcheck.availability.internal.adapter.AdapterFixtures.utf8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.chaekcheck.availability.internal.SearchField;
import com.chaekcheck.availability.internal.SearchQuery;
import com.chaekcheck.availability.internal.UpstreamException;

/**
 * Bookcube XML (cycle 07, ADR-0056). Fixtures saved 2026-10-05 from 울산동부도서관 (owned) and 부산광역시
 * 전자도서관(구독) (rental), with descriptions, author notes and images removed.
 */
class BookcubeFxAdapterTests {

	BookcubeFxAdapter adapter = new BookcubeFxAdapter();

	@Test
	void searchUrlPerFieldWithPages() {
		assertThat(adapter.searchUri("https://ebook.usdl.or.kr/FxLibrary", SearchQuery.of("사랑"), 2).toString())
			.isEqualTo("https://ebook.usdl.or.kr/FxLibrary/xml/productSearch/"
					+ "?keyword=%EC%82%AC%EB%9E%91&keyoption2=1&itemCount=10&page=2");
		assertThat(adapter.searchUri("http://elib.gyeongnam.go.kr:8000/FxLibrary_gj",
				SearchQuery.of("김영하", SearchField.AUTHOR), 1).toString())
			.startsWith("http://elib.gyeongnam.go.kr:8000/FxLibrary_gj/xml/productSearch/?keyword=")
			.contains("&keyoption2=2&");
		assertThat(adapter.searchUri("https://a.test/FxLibrary", SearchQuery.of("문학동네", SearchField.PUBLISHER), 1)
			.getRawQuery()).contains("keyoption2=3");
	}

	@Test
	void searchRowsHaveCountsAndBookPages() { // AC-7, AC-24
		ParsedPage page = adapter.parse(bytes("bookcube-fx/search-love-p1.xml"), null);

		assertThat(page.totalCount()).isEqualTo(128);
		assertThat(page.items()).hasSize(3);
		ParsedItem first = page.items().getFirst();
		assertThat(first).extracting(ParsedItem::title, ParsedItem::author, ParsedItem::publisher,
				ParsedItem::supplier, ParsedItem::holdings, ParsedItem::loaned, ParsedItem::reservations,
				ParsedItem::siteVerdict)
			.containsExactly("엄마, 오늘도 사랑해", "구작가", "예담", "교보 전자책", 2, 0, 0, null);
		URI search = adapter.searchUri("https://ebook.usdl.or.kr/FxLibrary", SearchQuery.of("사랑"), 1);
		URI book = adapter.detailUri(search, first.detailPath());
		assertThat(book.toString()).isEqualTo("https://ebook.usdl.or.kr/FxLibrary/product/view/?num=F0003069");
		assertThat(adapter.detailFetchUri(book).toString())
			.isEqualTo("https://ebook.usdl.or.kr/FxLibrary/xml/productView/?num=F0003069");
	}

	@Test
	void rentalRowsAreSubscriptions() { // AC-9
		ParsedPage page = adapter.parse(bytes("bookcube-fx/rental-search-love-p1.xml"), null);

		assertThat(page.items()).isNotEmpty()
			.allSatisfy(item -> assertThat(item.siteVerdict()).isEqualTo(SiteVerdict.SUBSCRIPTION));
	}

	@Test
	void detailCountsComeFromTheProductOnly() { // AC-7: 거짓말 학교 "대출 2/5"
		assertThat(adapter.parseDetail(bytes("bookcube-fx/view.xml"), null)).isEqualTo(ParsedDetail.counted(5, 2, 0));
		assertThat(adapter.parseDetail(bytes("bookcube-fx/rental-view.xml"), null))
			.isEqualTo(new ParsedDetail(0, 0, 0, SiteVerdict.SUBSCRIPTION));
	}

	@Test
	void aMissingBookOrAnotherShapeFails() {
		assertThatThrownBy(() -> adapter.parseDetail(bytes("bookcube-fx/view-missing.xml"), null))
			.isInstanceOf(UpstreamException.class);
		assertThatThrownBy(() -> adapter.parse(utf8("<html><body>점검 중</body></html>"), null))
			.isInstanceOf(UpstreamException.class);
		assertThatThrownBy(() -> adapter.parse(utf8("<Products><ProductListParam></ProductListParam></Products>"), null))
			.isInstanceOf(UpstreamException.class);
	}

	@Test
	void externalEntitiesAreNeverRead(@TempDir Path dir) throws Exception {
		Path secret = dir.resolve("secret.txt");
		Files.writeString(secret, "SECRET");
		String evil = """
				<?xml version="1.0" encoding="utf-8"?>
				<!DOCTYPE Product [<!ENTITY x SYSTEM "%s">]>
				<Product><num>1</num><name>&x;</name><volume>1</volume><nowLendCount>0</nowLendCount>
				<nowReserveCount>0</nowReserveCount></Product>""".formatted(secret.toUri());

		assertThatThrownBy(() -> adapter.parseDetail(utf8(evil), null)).isInstanceOf(UpstreamException.class)
			.hasMessageNotContaining("SECRET");
	}

}
