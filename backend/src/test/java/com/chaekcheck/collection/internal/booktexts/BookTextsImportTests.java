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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.support.TransactionOperations;

import com.chaekcheck.catalog.AiSearchIndex;
import com.chaekcheck.catalog.BookTextsTransfer;
import com.chaekcheck.catalog.MostHeldBooks;
import com.chaekcheck.catalog.MostHeldBooks.HeldBook;
import com.chaekcheck.picks.ListedBooks;
import com.chaekcheck.picks.ListedBooks.ListedBook;
import com.chaekcheck.picks.ListedBooks.Snapshot;

/**
 * {@link BookTextsImport} with fakes (cycle 24 handoff step 4-3; cycle 26 step 5): the file is checked before the
 * database is read, no listed book refuses the import without touching the search books, and every listed book goes in
 * once. Blurbs, covers and tags go to the catalog's {@link BookTextsTransfer}, and a file with only those kinds leaves
 * the search books alone. A file for another catalog export is refused before anything is read or written, unless
 * forced (AC-5).
 */
class BookTextsImportTests {

	private static final UUID EXPORT = UUID.fromString("0b8f2c64-5a1e-4d6f-9c1b-7d2e3f4a5b6c");

	private static final String FIRST = "# export_id=" + EXPORT + "\nkind\tbook_id\tsource\tmade_on\ttext\n";

	@TempDir
	Path dir;

	private final FakeTransfer transfer = new FakeTransfer();

	private final FakeIndex index = new FakeIndex();

	private final FakeHeld held = new FakeHeld(List.of());

	@Test
	void everyListedBookGoesInWithTheFileLines() throws Exception {
		FakeListed listed = new FakeListed(List.of(book(11), book(12), book(11)));

		BookTextsImport.Report report = command(listed, 0).run(file(FIRST + """
				search_line\t11\tintro\t2026-10-24\t우주를 다룬 교양서
				summary\t11\tintro\t2026-10-24\t모르는 글
				"""), false);

		assertThat(transfer.searchBooks).containsExactly(11L, 12L);
		assertThat(transfer.lines).extracting(BookTextsTransfer.Line::bookId).containsExactly(11L);
		assertThat(report.fileRows()).isEqualTo(2);
		assertThat(report.linesRead()).isEqualTo(1);
		assertThat(report.skippedKinds()).containsEntry("summary", 1);
		assertThat(report.forced()).isFalse();
		assertThat(transfer.summaries).isEmpty();
		assertThat(transfer.tags).isEmpty();
		assertThat(index.compacted).isOne();
	}

	@Test
	void summariesCoversAndTagsGoToTheCatalogWithTheSearchLines() throws Exception {
		BookTextsImport.Report report = command(new FakeListed(List.of(book(11))), 0).run(file(FIRST + """
				search_line\t11\tintro\t2026-10-24\t우주를 다룬 교양서
				for_you_blurb\t11\tintro\t2026-10-24\t별과 우주를 차근차근 풀어 주는 책
				tags\t11\t\t2026-10-24\tm01,s01,zz9
				cover_file\t11\t\t\t0123456789abcdef0123456789abcdef01234567
				"""), false);

		assertThat(transfer.searchCalls).isEqualTo(1);
		assertThat(transfer.summaries).extracting(BookTextsTransfer.Summary::bookId, BookTextsTransfer.Summary::text)
			.containsExactly(tuple(11L, "별과 우주를 차근차근 풀어 주는 책"));
		assertThat(transfer.covers)
			.containsExactly(new BookTextsTransfer.Cover(11, "0123456789abcdef0123456789abcdef01234567"));
		assertThat(transfer.tags).containsExactly(Map.entry(11L, List.of("m01", "s01", "zz9")));
		assertThat(report.blurbs()).isEqualTo(1);
		assertThat(report.tagBooks()).isEqualTo(1);
		// the fake dictionary knows m01 and s01 only
		assertThat(report.tagCodesSkipped()).isEqualTo(1);
		assertThat(report.covers()).isEqualTo(1);
	}

	@Test
	void aFileWithOnlySummaryKindsLeavesTheSearchBooksAlone() throws Exception {
		FakeListed listed = new FakeListed(List.of());

		BookTextsImport.Report report = command(listed, 0).run(file(FIRST + """
				for_you_blurb\t11\t\t2026-10-24\t짧은 글
				"""), false);

		assertThat(report.result()).isNull();
		assertThat(listed.reads).isZero();
		assertThat(transfer.searchCalls).isZero();
		assertThat(index.compacted).isZero();
		assertThat(transfer.summaries).hasSize(1);
	}

	@Test
	void theMostHeldBooksFollowTheListedOnesOnceEach() throws Exception {
		FakeHeld top = new FakeHeld(List.of(new HeldBook(30, "불편한 편의점", "김호연", new long[] { 1, 2, 3 }),
				new HeldBook(11, "코스모스", "칼 세이건", new long[] { 1, 2 }),
				new HeldBook(31, "작별인사", null, new long[] { 4 })));

		BookTextsImport.Report report = new BookTextsImport(new FakeListed(List.of(book(11))), top, 3, transfer, index,
				() -> Optional.of(EXPORT), TransactionOperations.withoutTransaction())
			.run(file(FIRST + "search_line\t30\tintro\t2026-10-25\t편의점에서 만난 사람들의 따뜻한 이야기\n"), false);

		assertThat(top.asked).containsExactly(3);
		assertThat(transfer.searchBooks).containsExactly(11L, 30L, 31L);
		assertThat(report.listed()).isEqualTo(1);
		assertThat(report.topOnly()).isEqualTo(2);
	}

	@Test
	void excludedMostHeldBooksAreLeftOutButListedOnesStay() throws Exception { // 2026-10-25, books without an intro
		FakeHeld top = new FakeHeld(List.of(new HeldBook(30, "불편한 편의점", "김호연", new long[] { 1 }),
				new HeldBook(11, "코스모스", null, new long[] { 1 }), new HeldBook(31, "작별인사", null, new long[] { 4 })));

		BookTextsImport.Report report = new BookTextsImport(new FakeListed(List.of(book(11))), top, 3, transfer, index,
				() -> Optional.of(EXPORT), TransactionOperations.withoutTransaction())
			.run(file(FIRST + "search_line\t30\tintro\t2026-10-25\t편의점에서 만난 사람들의 따뜻한 이야기\n"
					+ "search_exclude\t31\t\t\tempty_intro\nsearch_exclude\t11\t\t\tno_intro\n"), false);

		assertThat(transfer.searchBooks).containsExactly(11L, 30L);
		assertThat(report.topOnly()).isEqualTo(1);
		assertThat(report.excluded()).isEqualTo(1);
	}

	@Test
	void aFileOfAnotherExportIsRefusedUnlessForced() throws Exception { // AC-5
		FakeListed listed = new FakeListed(List.of(book(11)));
		Path file = file("# export_id=" + UUID.randomUUID() + "\nkind\tbook_id\tsource\tmade_on\ttext\n"
				+ "for_you_blurb\t11\t\t2026-10-24\t글\n");

		assertThatThrownBy(() -> command(listed, 0).run(file, false)).isInstanceOf(BookTextsFile.RefusedException.class)
			.hasMessageContaining(EXPORT.toString());
		assertThat(transfer.summaries).isEmpty();

		assertThat(command(listed, 0).run(file, true).forced()).isTrue();
		assertThat(transfer.summaries).hasSize(1);
	}

	@Test
	void aDatabaseWithoutAnExportRefusesTheFile() throws Exception {
		BookTextsImport command = new BookTextsImport(new FakeListed(List.of(book(11))), held, 0, transfer, index,
				Optional::empty, TransactionOperations.withoutTransaction());

		assertThatThrownBy(() -> command.run(file(FIRST + "for_you_blurb\t11\t\t2026-10-24\t글\n"), false))
			.isInstanceOf(BookTextsFile.RefusedException.class);
	}

	@Test
	void anExcludedBookNeedsItsReason() {
		assertThatThrownBy(() -> BookTextsFile.parse(List.of("# export_id=" + EXPORT, "kind\tbook_id\tsource\tmade_on\ttext",
				"search_exclude\t31\t\t\t"))).isInstanceOf(BookTextsFile.RefusedException.class)
			.hasMessageContaining("reason");
	}

	@Test
	void noListedBookRefusesAndKeepsTheSearchBooks() throws Exception {
		BookTextsImport command = command(new FakeListed(List.of()), 0);
		Path file = file(FIRST + "search_line\t11\tintro\t2026-10-24\t가\nfor_you_blurb\t11\t\t2026-10-24\t글\n");

		assertThatThrownBy(() -> command.run(file, false)).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("kept");
		assertThat(transfer.searchCalls).isZero();
		assertThat(transfer.summaries).isEmpty();
	}

	@Test
	void aRefusedFileStopsBeforeTheListsAreRead() throws Exception {
		FakeListed listed = new FakeListed(List.of(book(11)));
		Path file = file(FIRST + "search_line\t11\tintro\t2026-10-24\t앞\t뒤\n");

		assertThatThrownBy(() -> command(listed, 0).run(file, false)).isInstanceOf(BookTextsFile.RefusedException.class);
		assertThat(listed.reads).isZero();
		assertThat(transfer.searchCalls).isZero();
	}

	private BookTextsImport command(FakeListed listed, int top) {
		return new BookTextsImport(listed, held, top, transfer, index, () -> Optional.of(EXPORT),
				TransactionOperations.withoutTransaction());
	}

	private Path file(String text) throws Exception {
		Path file = Files.createTempFile(dir, "book-texts", ".tsv");
		Files.writeString(file, text, StandardCharsets.UTF_8);
		return file;
	}

	private static ListedBook book(long id) {
		return new ListedBook(id, "책 " + id, null, null, new long[] { 1 }, List.of("kaist"));
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

	private static final class FakeHeld implements MostHeldBooks {

		private final List<HeldBook> books;

		final List<Integer> asked = new ArrayList<>();

		FakeHeld(List<HeldBook> books) {
			this.books = books;
		}

		@Override
		public List<HeldBook> top(int count) {
			asked.add(count);
			return books.subList(0, Math.min(count, books.size()));
		}

	}

	private static final class FakeTransfer implements BookTextsTransfer {

		final List<Long> searchBooks = new ArrayList<>();

		final List<Line> lines = new ArrayList<>();

		final List<Summary> summaries = new ArrayList<>();

		final List<Cover> covers = new ArrayList<>();

		final Map<Long, List<String>> tags = new LinkedHashMap<>();

		int searchCalls;

		@Override
		public int upsertSummaries(Collection<Summary> summaries) {
			this.summaries.addAll(summaries);
			return summaries.size();
		}

		@Override
		public int upsertCovers(Collection<Cover> covers) {
			this.covers.addAll(covers);
			return covers.size();
		}

		@Override
		public int replaceTags(long bookId, Collection<String> codes, LocalDate madeOn) {
			tags.put(bookId, List.copyOf(codes));
			return (int) codes.stream().filter(code -> !List.of("m01", "s01").contains(code)).count();
		}

		@Override
		public int replaceTagDictionary(Collection<DictionaryRow> rows) {
			throw new AssertionError("book-texts carries no dictionary");
		}

		@Override
		public int replaceProfiles(Collection<BookProfile> profiles) {
			throw new AssertionError("book-texts carries no profiles");
		}

		@Override
		public SearchResult replaceSearchBooks(List<Long> bookIds, List<Line> lines, List<Written> written) {
			searchCalls++;
			searchBooks.addAll(bookIds);
			this.lines.addAll(lines);
			return new SearchResult(bookIds.size(), lines.size(), 0, 0);
		}

	}

	private static final class FakeIndex implements AiSearchIndex {

		int compacted;

		@Override
		public List<Candidate> find(List<String> words, Set<String> coreWords, Set<Long> planLibraries,
				Set<Long> excluded, int limit, Integer fromYear) {
			throw new AssertionError();
		}

		@Override
		public Map<Long, Integer> planCounts(Collection<Long> bookIds, Set<Long> planLibraries) {
			throw new AssertionError();
		}

		@Override
		public Map<Long, String> lines(Collection<Long> bookIds) {
			throw new AssertionError();
		}

		@Override
		public List<SearchBook> books() {
			throw new AssertionError();
		}

		@Override
		public String version() {
			throw new AssertionError();
		}

		@Override
		public void compact() {
			compacted++;
		}

	}

}
