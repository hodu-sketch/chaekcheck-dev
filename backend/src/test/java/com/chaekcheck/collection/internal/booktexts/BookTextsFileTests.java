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
import com.chaekcheck.foryou.ForYouTransfer.Blurb;
import com.chaekcheck.foryou.ForYouTransfer.Cover;

/**
 * {@link BookTextsFile} (cycle 24 handoff step 4-3): the header, kinds, tabs and line breaks, 300 characters. Cycle 23
 * step 3-3: the kinds for_you_blurb, tags and cover_file.
 */
class BookTextsFileTests {

	private static final String HEADER = "kind\tbook_id\tsource\tmade_on\ttext";

	@TempDir
	Path dir;

	@Test
	void readsTheSearchLinesAndCountsOtherKinds() throws Exception {
		Path file = write("\uFEFF" + HEADER + "\r\n" + "search_line\t11\tintro\t2026-10-24\t우주를 다룬 교양서\r\n"
				+ "summary\t11\tintro\t2026-10-24\t모르는 글\r\n" + "search_line\t12\tbibliographic\t2026-10-23\t짧은 소설\r\n"
				+ "summary\t12\tintro\t2026-10-24\t또 모르는 글\r\n");

		BookTextsFile.Contents contents = BookTextsFile.read(file);

		assertThat(contents.lines()).extracting(Line::bookId, Line::source, Line::madeOn, Line::text)
			.containsExactly(tuple(11L, Source.INTRO, LocalDate.of(2026, 10, 24), "우주를 다룬 교양서"),
					tuple(12L, Source.BIBLIOGRAPHIC, LocalDate.of(2026, 10, 23), "짧은 소설"));
		assertThat(contents.skippedKinds()).isEqualTo(Map.of("summary", 2));
		assertThat(contents.rows()).isEqualTo(4);
		assertThat(contents.hasForYouKinds()).isFalse();
	}

	/** Cycle 23 step 3-3: blurbs, tags and cover file keys; source is not read, cover_file reads no made_on. */
	@Test
	void readsTheForYouKinds() {
		BookTextsFile.Contents contents = BookTextsFile.parse(List.of(HEADER,
				"for_you_blurb\t11\t\t2026-10-24\t" + "가".repeat(300), "tags\t11\tany\t2026-10-24\tm01, s02,m01",
				"tags\t12\t\t2026-10-25\t", "cover_file\t11\t\t\t0123456789abcdef0123456789abcdef01234567",
				"search_line\t11\tintro\t2026-10-24\t우주"));

		assertThat(contents.blurbs()).extracting(Blurb::bookId, Blurb::madeOn)
			.containsExactly(tuple(11L, LocalDate.of(2026, 10, 24)));
		assertThat(contents.tags()).extracting(BookTextsFile.BookTags::bookId, BookTextsFile.BookTags::codes)
			.containsExactly(tuple(11L, List.of("m01", "s02")), tuple(12L, List.of()));
		assertThat(contents.covers()).containsExactly(new Cover(11, "0123456789abcdef0123456789abcdef01234567"));
		assertThat(contents.lines()).hasSize(1);
		assertThat(contents.skippedKinds()).isEmpty();
		assertThat(contents.hasForYouKinds()).isTrue();
	}

	@Test
	void everyBadForYouRowIsNamedWithItsRow() {
		assertThatThrownBy(() -> BookTextsFile.parse(List.of(HEADER, "for_you_blurb\t1\t\t2026-10-24\t" + "가".repeat(301),
				"for_you_blurb\t2\t\t2026-10-24\t ", "for_you_blurb\t3\t\t어제\t글", "tags\t4\t\t2026-10-24\tm01,,s-2",
				"cover_file\t5\t\t\t0123456789ABCDEF0123456789abcdef01234567", "cover_file\t0\t\t\tabc",
				"tags\t6\t\t2026-10-24\tm01", "tags\t6\t\t2026-10-24\ts01")))
			.isInstanceOf(BookTextsFile.RefusedException.class)
			.hasMessageContaining("7 problem(s)")
			.hasMessageContaining("row 2: a blurb must have 1 to 300 characters, book 1 has 301")
			.hasMessageContaining("row 3: a blurb")
			.hasMessageContaining("row 4: made_on")
			.hasMessageContaining("row 5: not a tag code (letters, digits, _; 1 to 20): '', 's-2'")
			.hasMessageContaining("row 6: a cover file key")
			.hasMessageContaining("row 7: a row needs a book id")
			.hasMessageContaining("row 9: a second tags row for book 6");
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
