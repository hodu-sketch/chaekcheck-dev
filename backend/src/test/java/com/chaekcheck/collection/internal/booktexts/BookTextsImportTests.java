package com.chaekcheck.collection.internal.booktexts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.chaekcheck.aisearch.AiSearchBookTexts;
import com.chaekcheck.aisearch.AiSearchBookTexts.Book;
import com.chaekcheck.aisearch.AiSearchBookTexts.Line;
import com.chaekcheck.aisearch.AiSearchBookTexts.Result;
import com.chaekcheck.picks.ListedBooks;
import com.chaekcheck.picks.ListedBooks.ListedBook;
import com.chaekcheck.picks.ListedBooks.Snapshot;

/**
 * {@link BookTextsImport} with fakes (cycle 24 handoff step 4-3): the file is checked before the database is read, no
 * listed book refuses the import without touching the search books, and every listed book goes in with its title and
 * author.
 */
class BookTextsImportTests {

	@TempDir
	Path dir;

	private final FakeTexts texts = new FakeTexts();

	@Test
	void everyListedBookGoesInWithTheFileLines() throws Exception {
		FakeListed listed = new FakeListed(List.of(book(11, "코스모스", "칼 세이건 지음"), book(12, "토지", null)));

		BookTextsImport.Report report = new BookTextsImport(listed, texts).run(file("""
				kind\tbook_id\tsource\tmade_on\ttext
				search_line\t11\tintro\t2026-10-24\t우주를 다룬 교양서
				for_you_blurb\t11\tintro\t2026-10-24\t23의 글
				"""));

		assertThat(texts.books).extracting(Book::bookId, Book::title, Book::author)
			.containsExactly(tuple(11L, "코스모스", "칼 세이건 지음"), tuple(12L, "토지", null));
		assertThat(texts.lines).extracting(Line::bookId).containsExactly(11L);
		assertThat(report.fileRows()).isEqualTo(2);
		assertThat(report.linesRead()).isEqualTo(1);
		assertThat(report.skippedKinds()).containsEntry("for_you_blurb", 1);
	}

	@Test
	void noListedBookRefusesAndKeepsTheSearchBooks() throws Exception {
		BookTextsImport command = new BookTextsImport(new FakeListed(List.of()), texts);
		Path file = file("kind\tbook_id\tsource\tmade_on\ttext\nsearch_line\t11\tintro\t2026-10-24\t가\n");

		assertThatThrownBy(() -> command.run(file)).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("kept");
		assertThat(texts.calls).isZero();
	}

	@Test
	void aRefusedFileStopsBeforeTheListsAreRead() throws Exception {
		FakeListed listed = new FakeListed(List.of(book(11, "코스모스", null)));
		Path file = file("kind\tbook_id\tsource\tmade_on\ttext\nsearch_line\t11\tintro\t2026-10-24\t앞\t뒤\n");

		assertThatThrownBy(() -> new BookTextsImport(listed, texts).run(file))
			.isInstanceOf(BookTextsFile.RefusedException.class);
		assertThat(listed.reads).isZero();
		assertThat(texts.calls).isZero();
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

}
