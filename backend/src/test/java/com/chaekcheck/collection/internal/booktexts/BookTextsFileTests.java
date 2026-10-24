package com.chaekcheck.collection.internal.booktexts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.chaekcheck.aisearch.AiSearchBookTexts.Line;
import com.chaekcheck.aisearch.AiSearchBookTexts.Source;

/** {@link BookTextsFile} (cycle 24 handoff step 4-3): the header, kinds, tabs and line breaks, 300 characters. */
class BookTextsFileTests {

	private static final String HEADER = "kind\tbook_id\tsource\tmade_on\ttext";

	@TempDir
	Path dir;

	@Test
	void readsTheSearchLinesAndCountsOtherKinds() throws Exception {
		Path file = write("\uFEFF" + HEADER + "\r\n" + "search_line\t11\tintro\t2026-10-24\t우주를 다룬 교양서\r\n"
				+ "for_you_blurb\t11\tintro\t2026-10-24\t23의 글\r\n" + "search_line\t12\tbibliographic\t2026-10-23\t짧은 소설\r\n"
				+ "for_you_blurb\t12\tintro\t2026-10-24\t또 23의 글\r\n");

		BookTextsFile.Contents contents = BookTextsFile.read(file);

		assertThat(contents.lines()).extracting(Line::bookId, Line::source, Line::madeOn, Line::text)
			.containsExactly(tuple(11L, Source.INTRO, LocalDate.of(2026, 10, 24), "우주를 다룬 교양서"),
					tuple(12L, Source.BIBLIOGRAPHIC, LocalDate.of(2026, 10, 23), "짧은 소설"));
		assertThat(contents.skippedKinds()).isEqualTo(Map.of("for_you_blurb", 2));
		assertThat(contents.rows()).isEqualTo(4);
	}

	@Test
	void threeHundredCharactersAreReadAndMoreRefuseTheWholeFile() {
		String longest = "가".repeat(300);
		assertThat(BookTextsFile.parse(List.of(HEADER, "search_line\t1\tintro\t2026-10-24\t" + longest)).lines())
			.hasSize(1);

		assertThatThrownBy(() -> BookTextsFile.parse(List.of(HEADER, "search_line\t1\tintro\t2026-10-24\t짧음",
				"search_line\t2\tintro\t2026-10-24\t" + longest + "가")))
			.isInstanceOf(BookTextsFile.RefusedException.class)
			.hasMessageContaining("row 3")
			.hasMessageContaining("301");
	}

	@Test
	void aTabOrALineBreakInTheTextRefusesTheFile() {
		assertThatThrownBy(() -> BookTextsFile.parse(List.of(HEADER, "search_line\t1\tintro\t2026-10-24\t앞\t뒤")))
			.isInstanceOf(BookTextsFile.RefusedException.class)
			.hasMessageContaining("row 2: 6 columns");
		// a line break splits the row in two: both halves have the wrong number of columns
		assertThatThrownBy(() -> BookTextsFile.parse(List.of(HEADER, "search_line\t1\tintro\t2026-10-24\t앞", "뒤")))
			.isInstanceOf(BookTextsFile.RefusedException.class)
			.hasMessageContaining("row 3: 1 columns");
	}

	@Test
	void aBlankRowIsNamed() {
		assertThatThrownBy(() -> BookTextsFile.parse(List.of(HEADER, "search_line\t1\tintro\t2026-10-24\t가", "")))
			.isInstanceOf(BookTextsFile.RefusedException.class)
			.hasMessageContaining("row 3: a blank row");
	}

	@Test
	void everyBadCellIsNamedWithItsRow() {
		assertThatThrownBy(() -> BookTextsFile.parse(List.of(HEADER, "search_line\tx\tintro\t2026-10-24\t가",
				"search_line\t2\tsummary\t2026-10-24\t가", "search_line\t3\tintro\t10/24\t가",
				"search_line\t4\tintro\t2026-10-24\t가", "search_line\t4\tintro\t2026-10-24\t나",
				"search_line\t0\tintro\t2026-10-24\t가")))
			.isInstanceOf(BookTextsFile.RefusedException.class)
			.hasMessageContaining("5 problem(s)")
			.hasMessageContaining("row 2: book_id")
			.hasMessageContaining("row 3: unknown line source")
			.hasMessageContaining("row 4: made_on")
			.hasMessageContaining("row 6: a second line for book 4")
			.hasMessageContaining("row 7:");
	}

	@Test
	void aWrongOrMissingHeaderRefusesTheFile() {
		assertThatThrownBy(() -> BookTextsFile.parse(List.of("book_id\ttext", "1\t가")))
			.isInstanceOf(BookTextsFile.RefusedException.class)
			.hasMessageContaining("header");
		assertThatThrownBy(() -> BookTextsFile.parse(List.of())).isInstanceOf(BookTextsFile.RefusedException.class);
	}

	private Path write(String text) throws Exception {
		Path file = dir.resolve("book-texts.tsv");
		Files.writeString(file, text, StandardCharsets.UTF_8);
		return file;
	}

}
