package com.chaekcheck.picks.internal.lists;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CoverFilesTests {

	@Test
	void namesTheCollectorsFileUnderTheWebServersCoversPath() { // user 2026-10-20, 안 A
		// sha1("https://image.yes24.com/goods/1/M"), the name the collector writes in covers/
		String name = CuratedLists.sha1("https://image.yes24.com/goods/1/M") + ".jpg";
		assertThat(CoverFiles.fileName("https://image.yes24.com/goods/1/M")).isEqualTo(name).hasSize(44);
		assertThat(CoverFiles.path("https://image.yes24.com/goods/1/M")).isEqualTo("/covers/" + name);
	}

}
