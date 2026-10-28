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

import com.chaekcheck.catalog.BookTextsTransfer.Cover;
import com.chaekcheck.catalog.BookTextsTransfer.Line;
import com.chaekcheck.catalog.BookTextsTransfer.Source;
import com.chaekcheck.catalog.BookTextsTransfer.Summary;

/**
 * {@link BookTextsFile} (cycle 24 handoff step 4-3): the header, kinds, tabs and line breaks, 300 characters. Cycle 23
 * step 3-3: the kinds for_you_blurb, tags and cover_file. Cycle 26: the first row names the catalog export, so every
 * data row number is one more than before the header moved to the second row.
 */
class BookTextsFileTests {

	private static final String EXPORT = "# export_id=0b8f2c64-5a1e-4d6f-9c1b-7d2e3f4a5b6c";

	private static final String HEADER = EXPORT + "\n" + "kind\tbook_id\tsource\tmade_on\ttext";

	@TempDir
	Path dir;

	@Test
	void readsTheSearchLinesAndCountsOtherKinds() throws Exception {
		Path file = write("\uFEFF" + EXPORT + "\r\n" + "kind\tbook_id\tsource\tmade_on\ttext\r\n" + "search_line\t11\tintro\t2026-10-24\t우주를 다룬 교양서\r\n"
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
		BookTextsFile.Contents contents = BookTextsFile.parse(rows(
				"for_you_blurb\t11\t\t2026-10-24\t" + "가".repeat(300), "tags\t11\tany\t2026-10-24\tm01, s02,m01",
				"tags\t12\t\t2026-10-25\t", "cover_file\t11\t\t\t0123456789abcdef0123456789abcdef01234567",
				"search_line\t11\tintro\t2026-10-24\t우주"));

		assertThat(contents.blurbs()).extracting(Summary::bookId, Summary::madeOn)
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
		assertThatThrownBy(() -> BookTextsFile.parse(rows( "for_you_blurb\t1\t\t2026-10-24\t" + "가".repeat(301),
				"for_you_blurb\t2\t\t2026-10-24\t ", "for_you_blurb\t3\t\t어제\t글", "tags\t4\t\t2026-10-24\tm01,,s-2",
				"cover_file\t5\t\t\t0123456789ABCDEF0123456789abcdef01234567", "cover_file\t0\t\t\tabc",
				"tags\t6\t\t2026-10-24\tm01", "tags\t6\t\t2026-10-24\ts01")))
			.isInstanceOf(BookTextsFile.RefusedException.class)
			.hasMessageContaining("7 problem(s)")
			.hasMessageContaining("row 3: a blurb must have 1 to 300 characters, book 1 has 301")
			.hasMessageContaining("row 4: a blurb")
			.hasMessageContaining("row 5: made_on")
			.hasMessageContaining("row 6: not a tag code (letters, digits, _; 1 to 20): '', 's-2'")
			.hasMessageContaining("row 7: a cover file key")
			.hasMessageContaining("row 8: a row needs a book id")
			.hasMessageContaining("row 10: a second tags row for book 6");
	}

	@Test
	void threeHundredCharactersAreReadAndMoreRefuseTheWholeFile() {
		String longest = "가".repeat(300);
		assertThat(BookTextsFile.parse(rows( "search_line\t1\tintro\t2026-10-24\t" + longest)).lines())
			.hasSize(1);

		assertThatThrownBy(() -> BookTextsFile.parse(rows( "search_line\t1\tintro\t2026-10-24\t짧음",
				"search_line\t2\tintro\t2026-10-24\t" + longest + "가")))
			.isInstanceOf(BookTextsFile.RefusedException.class)
			.hasMessageContaining("row 4")
			.hasMessageContaining("301");
	}

	@Test
	void aTabOrALineBreakInTheTextRefusesTheFile() {
		assertThatThrownBy(() -> BookTextsFile.parse(rows( "search_line\t1\tintro\t2026-10-24\t앞\t뒤")))
			.isInstanceOf(BookTextsFile.RefusedException.class)
			.hasMessageContaining("row 3: 6 columns");
		// a line break splits the row in two: both halves have the wrong number of columns
		assertThatThrownBy(() -> BookTextsFile.parse(rows( "search_line\t1\tintro\t2026-10-24\t앞", "뒤")))
			.isInstanceOf(BookTextsFile.RefusedException.class)
			.hasMessageContaining("row 4: 1 columns");
	}

	@Test
	void aBlankRowIsNamed() {
		assertThatThrownBy(() -> BookTextsFile.parse(rows( "search_line\t1\tintro\t2026-10-24\t가", "")))
			.isInstanceOf(BookTextsFile.RefusedException.class)
			.hasMessageContaining("row 4: a blank row");
	}

	@Test
	void everyBadCellIsNamedWithItsRow() {
		assertThatThrownBy(() -> BookTextsFile.parse(rows( "search_line\tx\tintro\t2026-10-24\t가",
				"search_line\t2\tsummary\t2026-10-24\t가", "search_line\t3\tintro\t10/24\t가",
				"search_line\t4\tintro\t2026-10-24\t가", "search_line\t4\tintro\t2026-10-24\t나",
				"search_line\t0\tintro\t2026-10-24\t가")))
			.isInstanceOf(BookTextsFile.RefusedException.class)
			.hasMessageContaining("5 problem(s)")
			.hasMessageContaining("row 3: book_id")
			.hasMessageContaining("row 4: unknown line source")
			.hasMessageContaining("row 5: made_on")
			.hasMessageContaining("row 7: a second line for book 4")
			.hasMessageContaining("row 8:");
	}

	@Test
	void aWrongOrMissingHeaderRefusesTheFile() {
		assertThatThrownBy(() -> BookTextsFile.parse(List.of(EXPORT, "book_id\ttext", "1\t가")))
			.isInstanceOf(BookTextsFile.RefusedException.class)
			.hasMessageContaining("header");
		assertThatThrownBy(() -> BookTextsFile.parse(List.of())).isInstanceOf(BookTextsFile.RefusedException.class);
	}

	/** The export row, the header, then {@code data}. */
	private static List<String> rows(String... data) {
		List<String> rows = new java.util.ArrayList<>(List.of(HEADER.split("\n")));
		rows.addAll(List.of(data));
		return rows;
	}

	@Test
	void theFirstRowMustNameTheExport() {
		assertThatThrownBy(() -> BookTextsFile.parse(List.of("kind\tbook_id\tsource\tmade_on\ttext",
				"search_line\t1\tintro\t2026-10-24\t가")))
			.isInstanceOf(BookTextsFile.RefusedException.class)
			.hasMessageContaining("# export_id=");
		assertThatThrownBy(() -> BookTextsFile.parse(List.of("# export_id=abc", "kind\tbook_id\tsource\tmade_on\ttext")))
			.isInstanceOf(BookTextsFile.RefusedException.class)
			.hasMessageContaining("not a UUID");
		assertThat(BookTextsFile.parse(rows()).exportId()).hasToString("0b8f2c64-5a1e-4d6f-9c1b-7d2e3f4a5b6c");
	}

	private Path write(String text) throws Exception {
		Path file = dir.resolve("book-texts.tsv");
		Files.writeString(file, text, StandardCharsets.UTF_8);
		return file;
	}

}
