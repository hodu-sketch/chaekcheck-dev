package com.chaekcheck.collection.internal.booktexts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.support.TransactionOperations;

import com.chaekcheck.aisearch.AiSearchBookTexts;
import com.chaekcheck.aisearch.AiSearchBookTexts.Book;
import com.chaekcheck.aisearch.AiSearchBookTexts.Line;
import com.chaekcheck.aisearch.AiSearchBookTexts.Result;
import com.chaekcheck.foryou.ForYouTransfer;
import com.chaekcheck.picks.BookTagTransfer;
import com.chaekcheck.picks.ListedBooks;
import com.chaekcheck.picks.ListedBooks.ListedBook;
import com.chaekcheck.picks.ListedBooks.Snapshot;

/**
 * {@link BookTextsImport} with fakes (cycle 24 handoff step 4-3): the file is checked before the database is read, no
 * listed book refuses the import without touching the search books, and every listed book goes in with its title and
 * author. Cycle 23 step 3-3: blurbs and covers go to foryou, tags to picks, and a file with only those kinds leaves the
 * search books alone.
 */
class BookTextsImportTests {

	@TempDir
	Path dir;

	private final FakeTexts texts = new FakeTexts();

	private final FakeForYou forYou = new FakeForYou();

	private final FakeTags tags = new FakeTags();

	@Test
	void everyListedBookGoesInWithTheFileLines() throws Exception {
		FakeListed listed = new FakeListed(List.of(book(11, "코스모스", "칼 세이건 지음"), book(12, "토지", null)));

		BookTextsImport.Report report = command(listed).run(file("""
				kind\tbook_id\tsource\tmade_on\ttext
				search_line\t11\tintro\t2026-10-24\t우주를 다룬 교양서
				summary\t11\tintro\t2026-10-24\t모르는 글
				"""));

		assertThat(texts.books).extracting(Book::bookId, Book::title, Book::author)
			.containsExactly(tuple(11L, "코스모스", "칼 세이건 지음"), tuple(12L, "토지", null));
		assertThat(texts.lines).extracting(Line::bookId).containsExactly(11L);
		assertThat(report.fileRows()).isEqualTo(2);
		assertThat(report.linesRead()).isEqualTo(1);
		assertThat(report.skippedKinds()).containsEntry("summary", 1);
		assertThat(forYou.blurbs).isEmpty();
		assertThat(tags.replaced).isEmpty();
	}

	@Test
	void theForYouKindsGoToForYouAndPicksWithTheSearchLines() throws Exception {
		FakeListed listed = new FakeListed(List.of(book(11, "코스모스", null)));

		BookTextsImport.Report report = command(listed).run(file("""
				kind\tbook_id\tsource\tmade_on\ttext
				search_line\t11\tintro\t2026-10-24\t우주를 다룬 교양서
				for_you_blurb\t11\tintro\t2026-10-24\t별과 우주를 차근차근 풀어 주는 책
				tags\t11\t\t2026-10-24\tm01,s01,zz9
				cover_file\t11\t\t\t0123456789abcdef0123456789abcdef01234567
				"""));

		assertThat(texts.calls).isEqualTo(1);
		assertThat(forYou.blurbs).extracting(ForYouTransfer.Blurb::bookId, ForYouTransfer.Blurb::blurb)
			.containsExactly(tuple(11L, "별과 우주를 차근차근 풀어 주는 책"));
		assertThat(forYou.covers).containsExactly(new ForYouTransfer.Cover(11, "0123456789abcdef0123456789abcdef01234567"));
		assertThat(tags.replaced).containsExactly(Map.entry(11L, List.of("m01", "s01", "zz9")));
		assertThat(report.blurbs()).isEqualTo(1);
		assertThat(report.tagBooks()).isEqualTo(1);
		// the fake dictionary knows m01 and s01 only
		assertThat(report.tagCodesSkipped()).isEqualTo(1);
		assertThat(report.covers()).isEqualTo(1);
	}

	@Test
	void aFileWithOnlyForYouKindsLeavesTheSearchBooksAlone() throws Exception {
		FakeListed listed = new FakeListed(List.of());

		BookTextsImport.Report report = command(listed).run(file("""
				kind\tbook_id\tsource\tmade_on\ttext
				for_you_blurb\t11\t\t2026-10-24\t짧은 글
				"""));

		assertThat(report.result()).isNull();
		assertThat(listed.reads).isZero();
		assertThat(texts.calls).isZero();
		assertThat(forYou.blurbs).hasSize(1);
	}

	@Test
	void noListedBookRefusesAndKeepsTheSearchBooks() throws Exception {
		BookTextsImport command = command(new FakeListed(List.of()));
		Path file = file("kind\tbook_id\tsource\tmade_on\ttext\nsearch_line\t11\tintro\t2026-10-24\t가\n"
				+ "for_you_blurb\t11\t\t2026-10-24\t글\n");

		assertThatThrownBy(() -> command.run(file)).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("kept");
		assertThat(texts.calls).isZero();
		assertThat(forYou.blurbs).isEmpty();
	}

	@Test
	void aRefusedFileStopsBeforeTheListsAreRead() throws Exception {
		FakeListed listed = new FakeListed(List.of(book(11, "코스모스", null)));
		Path file = file("kind\tbook_id\tsource\tmade_on\ttext\nsearch_line\t11\tintro\t2026-10-24\t앞\t뒤\n");

		assertThatThrownBy(() -> command(listed).run(file)).isInstanceOf(BookTextsFile.RefusedException.class);
		assertThat(listed.reads).isZero();
		assertThat(texts.calls).isZero();
	}

	private BookTextsImport command(FakeListed listed) {
		return new BookTextsImport(listed, texts, forYou, tags, TransactionOperations.withoutTransaction());
	}

	private Path file(String text) throws Exception {
		Path file = dir.resolve("book-texts.tsv");
		Files.writeString(file, text, StandardCharsets.UTF_8);
		return file;
	}

	private static ListedBook book(long id, String title, String author) {
		return new ListedBook(id, title, author, null, new long[] { 1 }, List.of("kaist"));
	}

	private static final class FakeListed implements ListedBooks {

		private final List<ListedBook> books;

		int reads;

		FakeListed(List<ListedBook> books) {
			this.books = books;
		}

		@Override
		public Snapshot current() {
			throw new AssertionError("the import must not use the server cache");
		}

		@Override
		public Snapshot loadFromDb() {
			reads++;
			return new Snapshot(Instant.EPOCH, books);
		}

	}

	private static final class FakeTexts implements AiSearchBookTexts {

		final List<Book> books = new ArrayList<>();

		final List<Line> lines = new ArrayList<>();

		int calls;

		@Override
		public Result replace(List<Book> books, List<Line> lines) {
			calls++;
			this.books.addAll(books);
			this.lines.addAll(lines);
			return new Result(books.size(), lines.size(), 0);
		}

	}

	private static final class FakeForYou implements ForYouTransfer {

		final List<Blurb> blurbs = new ArrayList<>();

		final List<Cover> covers = new ArrayList<>();

		@Override
		public long replaceAllNeighbours(Iterator<Neighbours> rows, LocalDate computedOn) {
			throw new AssertionError("book-texts carries no neighbours");
		}

		@Override
		public long upsertNeighbours(Iterator<Neighbours> rows, LocalDate computedOn) {
			throw new AssertionError("book-texts carries no neighbours");
		}

		@Override
		public int upsertBlurbs(Collection<Blurb> blurbs) {
			this.blurbs.addAll(blurbs);
			return blurbs.size();
		}

		@Override
		public int upsertCovers(Collection<Cover> covers) {
			this.covers.addAll(covers);
			return covers.size();
		}

	}

	private static final class FakeTags implements BookTagTransfer {

		final Map<Long, List<String>> replaced = new LinkedHashMap<>();

		@Override
		public int upsertDictionary(Collection<DictionaryRow> rows) {
			throw new AssertionError("book-texts carries no dictionary");
		}

		@Override
		public int replaceTags(long bookId, Collection<String> codes, LocalDate madeOn) {
			replaced.put(bookId, List.copyOf(codes));
			return (int) codes.stream().filter(code -> !List.of("m01", "s01").contains(code)).count();
		}

	}

}
