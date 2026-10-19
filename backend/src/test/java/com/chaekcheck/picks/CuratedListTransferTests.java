package com.chaekcheck.picks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import com.chaekcheck.catalog.BookLookup;
import com.chaekcheck.catalog.normalize.CatalogText;
import com.chaekcheck.library.LibraryCatalog;
import com.chaekcheck.library.SearchTarget;
import com.chaekcheck.picks.CuratedListTransfer.ListFileException;

/**
 * {@link CuratedListTransfer} against db-test (cycle 21 handoff step 4-4; ADR-0123, ADR-0124): the file checks with
 * their line numbers, the fixed edition matched to our book, the list part, and {@code replace} leaving the librarian
 * rows and the other covers alone (AC-10). Everything is written in the test transaction.
 */
@SpringBootTest
@Transactional
class CuratedListTransferTests {

	static final String FIELDS = """
			list\tcode\tname\tposition
			leedongjin\tforeign_fiction\t외국 소설\t1
			leedongjin\tscience\t과학·의학\t2
			snu100\tkorean_lit\t한국문학\t1
			kaist\tnature\t자연\t1
			kaist\tai\tAI\t2
			""";

	static final String KAIST_HEADER = "year\tfield\tposition\ttitle\tauthor\tpublisher\tisbn13\tcover_url\tpinned_title"
			+ "\tpinned_author\tpinned_publisher\tpinned_isbn13\tpinned_by\tnote\n";

	static final String LEEDONGJIN_HEADER = "month\tposition\tyear_book\tvideo_id\tfield\ttitle\tauthor\tpublisher\tisbn13"
			+ "\tcover_url\tpinned_title\tpinned_author\tpinned_publisher\tpinned_isbn13\tpinned_by\tchecked_on\tnote\n";

	static final String SNU_HEADER = "toc_no\tfield\ttitle\tauthor\tcover_url\tpinned_title\tpinned_author"
			+ "\tpinned_publisher\tpinned_isbn13\tpinned_by\tnote\n";

	@Autowired
	CuratedListTransfer transfer;

	@Autowired
	BookLookup lookup;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	LibraryCatalog libraryCatalog;

	@TempDir
	Path dir;

	PicksTestData data;

	List<Long> plan;

	String today;

	@BeforeEach
	void books() {
		data = new PicksTestData(jdbc);
		plan = libraryCatalog.planSearch(Set.of(), Set.of()).stream().map(SearchTarget::id).toList();
		today = LocalDate.now().toString();
		// a fixed edition's ISBN
		data.ourBook(920001, "9799990000110", "히스토리아 비테이", null, plan.get(0));
		// a fixed edition by title, author and publisher: two publishers, the second held more
		data.ourBook(920002, null, "구운몽", "김만중", plan.get(0));
		data.ourBook(920003, null, "구운몽", "김만중", plan.get(0), plan.get(1), plan.get(2));
		data.ourBook(920004, null, "구운몽", "김만중", plan.get(0), plan.get(1));
		jdbc.sql("UPDATE catalog.book SET publisher_norm = CASE id WHEN 920003 THEN :other ELSE :same END "
				+ "WHERE id IN (920002, 920003, 920004)")
			.param("same", CatalogText.publisherNorm("명현 global soft"))
			.param("other", CatalogText.publisherNorm("스타북스"))
			.update();
		// a row without a fixed edition, by its own ISBN
		data.ourBook(920005, "9799990000127", "맡겨진 소녀", null, plan.get(1));
	}

	@Test
	void missingFolderOrFileStops() throws IOException {
		assertThatThrownBy(() -> transfer.check(dir.resolve("none"))).isInstanceOf(ListFileException.class)
			.hasMessageContaining("does not exist");
		write(FIELDS, LEEDONGJIN_HEADER, SNU_HEADER);
		Files.delete(dir.resolve("snu100.tsv"));
		assertThatThrownBy(() -> transfer.check(dir)).isInstanceOf(ListFileException.class)
			.hasMessageContaining("snu100.tsv")
			.hasMessageContaining("missing");
	}

	@Test
	void aWrongValueNamesTheFileTheLineAndTheColumn() throws IOException {
		write(FIELDS, LEEDONGJIN_HEADER + "2026-06\t1\t\tlMx0zEd1qtY\tscience\t히스토리아 비테이\t\t\t\t\t\t\t\t\t\t\t\n"
				+ "2026-13\t1\t\tUvJAIskB0qg\tscience\t가\t\t\t\t\t\t\t\t\t\t\t\n", SNU_HEADER);
		assertThatThrownBy(() -> transfer.check(dir)).isInstanceOfSatisfying(ListFileException.class, ex -> {
			assertThat(ex.file()).isEqualTo("leedongjin.tsv");
			assertThat(ex.line()).isEqualTo(3);
			assertThat(ex).hasMessageContaining("month");
		});

		write(FIELDS, LEEDONGJIN_HEADER, SNU_HEADER + "3\tkorean_lit\t구운몽\t\t\t\t\t\t\tcollectorx\t\n");
		assertThatThrownBy(() -> transfer.check(dir)).hasMessageContaining("snu100.tsv line 2")
			.hasMessageContaining("pinned_by");
		write(FIELDS, LEEDONGJIN_HEADER, SNU_HEADER + "3\tsocial\t구운몽\t\t\t\t\t\t\t\t\n");
		assertThatThrownBy(() -> transfer.check(dir)).hasMessageContaining("field");
		write(FIELDS, LEEDONGJIN_HEADER, SNU_HEADER + "3\tkorean_lit\t구운몽\t\thttp://x.example/a.jpg\t\t\t\t\t\t\n");
		assertThatThrownBy(() -> transfer.check(dir)).hasMessageContaining("cover_url");
		write(FIELDS + "snu100\tkorean_lit\t또\t2\n", LEEDONGJIN_HEADER, SNU_HEADER);
		assertThatThrownBy(() -> transfer.check(dir)).hasMessageContaining("fields.tsv line 7");
		write(FIELDS, "month\tposition\n", SNU_HEADER);
		assertThatThrownBy(() -> transfer.check(dir)).hasMessageContaining("no column");
	}

	@Test
	void readMatchesTheFixedEditionsAndCarriesUsedCovers() throws IOException { // AC-6, AC-15
		String used = "https://image.yes24.com/goods/1/M";
		write(FIELDS,
				LEEDONGJIN_HEADER
						+ "2026-06\t1\t\tlMx0zEd1qtY\tscience\t히스토리아 비테이\t최재천\t지식서재\t9791190266093\t" + used
						+ "\t히스토리아 비테이\t최재천\t지식서재\t9799990000110\tcollector\t" + today + "\t\n"
						+ "2023-12\t1\t2023\tZZuD0EDOfwY\tforeign_fiction\t맡겨진 소녀\t클레어 키건\t다산책방\t9799990000127\t"
						+ "\t\t\t\t\t\t2026-09-01\t\n",
				SNU_HEADER + "3\tkorean_lit\t구운몽\t김만중\t\t구운몽\t김만중\t명현 global soft\t\tmanual\t\n"
						+ "17\tkorean_lit\t광장\t최인훈\thttps://x.example/missing.jpg\t\t\t\t\t\t\n");
		cover(used, new byte[] { (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 1, 2 });
		cover("https://x.example/unused.jpg", new byte[] { (byte) 0xFF, (byte) 0xD8, (byte) 0xFF });

		CuratedListTransfer.ListPart part = transfer.read(dir, lookup);

		assertThat(part.counts()).containsEntry("leedongjin", new CuratedListTransfer.Counts(2, 2, 0))
			.containsEntry("snu100", new CuratedListTransfer.Counts(2, 1, 1));
		// 920003 (스타북스) is held most but is another publisher: of the two 명현 editions the one held more
		assertThat(part.json()).contains("\"bookId\":920001", "\"bookId\":920005", "\"bookId\":920004")
			.doesNotContain("920003", "920002")
			.contains("\"sourceUrl\":\"" + used + "\"")
			.doesNotContain("unused.jpg", "missing.jpg\",\"fetchedAt");
		assertThat(part.warnings()).anySatisfy(w -> assertThat(w).contains("ZZuD0EDOfwY", "2026-09-01"))
			.anySatisfy(w -> assertThat(w).contains("snu100.tsv line 3", "no cover file"))
			.noneSatisfy(w -> assertThat(w).contains("lMx0zEd1qtY"));
		// the files' own notes and who chose the edition stay on the PC
		assertThat(part.json()).doesNotContain("pinnedBy", "checkedOn", "manual", "collector", "note");
	}

	@Test
	void replaceWritesTheTwoListsOnlyAndKeepsTheOtherCovers() throws IOException { // AC-10
		data.run();
		data.pick("1", "11", null, "2026-10", null, "가", null, null);
		long librarianCover = data.cover("1", "https://www.nl.go.kr/afile/fileDownload/a");
		jdbc.sql("""
				INSERT INTO picks.cover (source_url, content_type, image, byte_size, fetched_at)
				VALUES ('https://image.yes24.com/goods/old/M', 'image/jpeg', '\\x01', 1, now())""").update();
		String used = "https://image.yes24.com/goods/1/M";
		write(FIELDS,
				LEEDONGJIN_HEADER + "2026-06\t1\t\tlMx0zEd1qtY\tscience\t히스토리아 비테이\t\t\t9799990000110\t" + used
						+ "\t\t\t\t\t\t" + today + "\t\n",
				SNU_HEADER + "3\tkorean_lit\t구운몽\t김만중\t\t구운몽\t김만중\t명현 global soft\t\tmanual\t\n");
		cover(used, new byte[] { (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 9 });
		String json = transfer.read(dir, lookup).json();

		CuratedListTransfer.Replaced first = transfer.replace(json);
		CuratedListTransfer.Replaced again = transfer.replace(json);

		assertThat(first).isEqualTo(new CuratedListTransfer.Replaced(5, 2, 1, 1));
		assertThat(again).isEqualTo(new CuratedListTransfer.Replaced(5, 2, 0, 0));
		assertThat(jdbc.sql("""
				SELECT list_key || ':' || field_code || ':' || position || ':' || title || ':' || coalesce(book_id, 0)
				  || ':' || coalesce(to_char(recommended_month, 'YYYY-MM'), '-') || ':' || coalesce(video_id, '-')
				  || ':' || coalesce(toc_no, 0) || ':' || coalesce(pinned_publisher, '-')
				FROM picks.list_book ORDER BY list_key, position""").query(String.class).list())
			.containsExactly("leedongjin:science:1:히스토리아 비테이:920001:2026-06:lMx0zEd1qtY:0:-",
					"librarian:11:1:가:0:2026-10:-:0:-", "snu100:korean_lit:3:구운몽:920004:-:-:3:명현 global soft");
		assertThat(jdbc.sql("SELECT source_url FROM picks.cover ORDER BY source_url").query(String.class).list())
			.containsExactly(used, "https://www.nl.go.kr/afile/fileDownload/a");
		assertThat(jdbc.sql("SELECT id FROM picks.cover WHERE source_url LIKE '%afile%'").query(Long.class).single())
			.isEqualTo(librarianCover);
		assertThat(jdbc.sql("SELECT byte_size || ':' || content_type FROM picks.cover WHERE source_url = :url")
			.param("url", used)
			.query(String.class)
			.single()).isEqualTo("4:image/jpeg");
		assertThatThrownBy(() -> transfer.replace("{\"x\":1}")).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void kaistFileChecks() throws IOException { // user 2026-10-19
		write(FIELDS, LEEDONGJIN_HEADER, SNU_HEADER,
				KAIST_HEADER + "2026\tnature\t1\t가\t\t\t\t\t\t\t\t\t\t\n" + "2026\tai\t1\t나\t\t\t\t\t\t\t\t\t\t\n");
		assertThatThrownBy(() -> transfer.check(dir)).hasMessageContaining("kaist.tsv line 3")
			.hasMessageContaining("position");
		write(FIELDS, LEEDONGJIN_HEADER, SNU_HEADER, KAIST_HEADER + "2026\tkorean_lit\t1\t가\t\t\t\t\t\t\t\t\t\t\n");
		assertThatThrownBy(() -> transfer.check(dir)).hasMessageContaining("kaist.tsv line 2")
			.hasMessageContaining("is not a kaist field");
		write(FIELDS, LEEDONGJIN_HEADER, SNU_HEADER, KAIST_HEADER + "2026\tai\t0\t가\t\t\t\t\t\t\t\t\t\t\n");
		assertThatThrownBy(() -> transfer.check(dir)).hasMessageContaining("position");
		write(FIELDS, LEEDONGJIN_HEADER, SNU_HEADER, KAIST_HEADER + "2026\tai\t1\t가\t\t\t97912\t\t\t\t\t\t\t\n");
		assertThatThrownBy(() -> transfer.check(dir)).hasMessageContaining("isbn13");
		write(FIELDS, LEEDONGJIN_HEADER, SNU_HEADER, KAIST_HEADER + "\tai\t1\t가\t\t\t\t\t\t\t\t\t\t\n");
		assertThatThrownBy(() -> transfer.check(dir)).hasMessageContaining("year"); // user 2026-10-20
		write(FIELDS.replace("kaist\tai", "kaistx\tai"), LEEDONGJIN_HEADER, SNU_HEADER, KAIST_HEADER);
		assertThatThrownBy(() -> transfer.check(dir)).hasMessageContaining("fields.tsv line 6")
			.hasMessageContaining("list");
		write(FIELDS, LEEDONGJIN_HEADER, SNU_HEADER, "field\ttitle\n");
		assertThatThrownBy(() -> transfer.check(dir)).hasMessageContaining("kaist.tsv line 1")
			.hasMessageContaining("no column");
	}

	@Test
	void kaistRowsMatchByTheirIsbnThenTitleAndAuthorAndKeepTheirPlaces() throws IOException { // user 2026-10-19
		write(FIELDS, LEEDONGJIN_HEADER, SNU_HEADER, KAIST_HEADER
				+ "2025\tai\t1\t맡겨진 소녀\t클레어 키건\t다산책방\t9799990000127\t\t\t\t\t\t\tKAIST 추천번호 1\n"
				+ "2026\tnature\t2\t없는 책\t누구\t어디\t\t\t\t\t\t\t\t\n"
				+ "2026\tnature\t1\t히스토리아 비테이\t\t\t\t\t히스토리아 비테이\t\t\t9799990000110\tmanual\t\n");

		CuratedListTransfer.ListPart part = transfer.read(dir, lookup);

		assertThat(part.counts()).containsEntry("kaist", new CuratedListTransfer.Counts(3, 2, 1));
		transfer.replace(part.json());
		assertThat(jdbc.sql("""
				SELECT year_book || ':' || field_code || ':' || position || ':' || title || ':' || coalesce(book_id, 0) || ':'
				  || coalesce(publisher, '-') || ':' || coalesce(isbn13, '-')
				FROM picks.list_book WHERE list_key = 'kaist' ORDER BY year_book, position""").query(String.class).list())
			.containsExactly("2025:ai:1:맡겨진 소녀:920005:다산책방:9799990000127",
					"2026:nature:1:히스토리아 비테이:920001:-:-", "2026:nature:2:없는 책:0:어디:-");
	}

	private void write(String fields, String leedongjin, String snu100) throws IOException {
		write(fields, leedongjin, snu100, KAIST_HEADER);
	}

	private void write(String fields, String leedongjin, String snu100, String kaist) throws IOException {
		Files.writeString(dir.resolve("fields.tsv"), fields, StandardCharsets.UTF_8);
		Files.writeString(dir.resolve("leedongjin.tsv"), leedongjin, StandardCharsets.UTF_8);
		Files.writeString(dir.resolve("snu100.tsv"), snu100, StandardCharsets.UTF_8);
		Files.writeString(dir.resolve("kaist.tsv"), kaist, StandardCharsets.UTF_8);
	}

	private void cover(String url, byte[] jpeg) throws IOException {
		Path covers = Files.createDirectories(dir.resolve("covers"));
		try {
			String name = HexFormat.of()
				.formatHex(MessageDigest.getInstance("SHA-1").digest(url.getBytes(StandardCharsets.UTF_8)));
			Files.write(covers.resolve(name + ".jpg"), jpeg);
		}
		catch (java.security.NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

}
