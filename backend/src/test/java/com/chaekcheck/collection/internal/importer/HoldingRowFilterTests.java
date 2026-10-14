package com.chaekcheck.collection.internal.importer;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The one rule of the import and the library insert (cycle 18, ADR-0110): each reason and their order. */
class HoldingRowFilterTests {

	static final String HOST = "hs_partners.yes24library.com";

	static final String URL = "https://hs_partners.yes24library.com/ebook/detail/?goods_id=1";

	@Test
	void anEbookOnTheLibraryHostPasses() {
		assertThat(HoldingRowFilter.skipReason(null, "EBOOK", null, URL, "1984", HOST)).isNull();
		assertThat(HoldingRowFilter.skipReason(null, "KIDS_BOOK", "book/epub", URL, "1984", HOST)).isNull();
	}

	@Test
	void eachReasonInOrder() {
		assertThat(HoldingRowFilter.skipReason("2026-10-01", "AUDIOBOOK", null, null, null, HOST)).isEqualTo("removed");
		assertThat(HoldingRowFilter.skipReason(null, "AUDIOBOOK", null, URL, "t", HOST)).isEqualTo("kind_audiobook");
		assertThat(HoldingRowFilter.skipReason(null, "VIDEO_BOOK", null, URL, "t", HOST)).isEqualTo("kind_video_book");
		assertThat(HoldingRowFilter.skipReason(null, null, null, URL, "t", HOST)).isEqualTo("kind_null");
		assertThat(HoldingRowFilter.skipReason(null, "EBOOK", " book/wma ", URL, "t", HOST))
			.isEqualTo("kind_sound_video");
		assertThat(HoldingRowFilter.skipReason(null, "EBOOK", null, " ", "t", HOST)).isEqualTo("no_detail_url");
		assertThat(HoldingRowFilter.skipReason(null, "EBOOK", null, "javascript:go(1)", "t", HOST))
			.isEqualTo("detail_url_not_http");
		assertThat(HoldingRowFilter.skipReason(null, "EBOOK", null, "https://other.test/x", "t", HOST))
			.isEqualTo("detail_host_mismatch");
		assertThat(HoldingRowFilter.skipReason(null, "EBOOK", null, URL, " ", HOST)).isEqualTo("no_title");
	}

}
