package com.chaekcheck.collection.internal.importer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The national bibliography records kept as read (plan ADR-0069, cycle 08 handoff step 3, AC-15): the first lookup
 * fills the table and writes the fingerprint last, the next one reads the table and not the files, a changed file is
 * read again, a stopped rewrite leaves no fingerprint, and both ways give the same ISBNs.
 */
@SpringBootTest
class NationalBibliographyTests {

	static final String TTL = """
			@prefix nlk:   <http://lod.nl.go.kr/resource/> .
			@prefix dc:    <http://purl.org/dc/elements/1.1/> .
			@prefix dcterms: <http://purl.org/dc/terms/> .
			@prefix bibo:  <http://purl.org/ontology/bibo/> .

			nlk:KMO1  a                     bibo:Book ;
			        bibo:isbn                       "978-89-546-8215-2 (03810)" ;
			        dc:creator                      "한강 지음" ;
			        dc:publisher                    "문학동네#" ;
			        dcterms:title                   "(장편소설) 작별하지 않는다" .

			nlk:KMO2  a                     bibo:Book ;
			        bibo:isbn                       "9788900000009" ;
			        dc:creator                      "가나다" ;
			        dc:publisher                    "라마바" ;
			        dcterms:title                   "모호한 책" .

			nlk:KMO3  a                     bibo:Book ;
			        bibo:isbn                       "9788900000016" ;
			        dc:creator                      "가나다 저" ;
			        dc:publisher                    "라마바" ;
			        dcterms:title                   "모호한 책" .

			nlk:KMO4  a                     bibo:Book ;
			        bibo:isbn                       "9788900000023", "9788900000030" ;
			        dc:creator                      "두 ISBN" ;
			        dc:publisher                    "출판사" ;
			        dcterms:title                   "두 판 \\"따옴표\\" 책" .

			nlk:KMO5  a                     bibo:Book ;
			        dc:creator                      "무 ISBN" ;
			        dcterms:title                   "ISBN 없는 기록" .
			""";

	static final Instant MODIFIED = Instant.parse("2026-08-07T00:00:00Z");

	@Autowired
	DataSource dataSource;

	@Autowired
	JdbcClient jdbc;

	@TempDir
	Path dir;

	NationalBibliographyStore store;

	Clock clock = Clock.fixed(Instant.parse("2026-10-11T03:00:00Z"), ZoneOffset.UTC);

	Set<String> wanted;

	Path file;

	@BeforeEach
	void setUp() throws IOException {
		store = new NationalBibliographyStore(dataSource);
		wanted = Set.of(NationalBibliography.keyOf("작별하지 않는다", "한강", "문학동네"),
				NationalBibliography.keyOf("모호한 책", "가나다", "라마바"),
				NationalBibliography.keyOf("두 판 \"따옴표\" 책", "두 ISBN", "출판사"));
		file = Files.createDirectories(dir.resolve("book_ttl")).resolve("book_0.ttl");
		Files.writeString(file, TTL, StandardCharsets.UTF_8);
		Files.setLastModifiedTime(file, FileTime.from(MODIFIED));
	}

	@AfterEach
	void empty() {
		jdbc.sql("TRUNCATE collection.national_bibliography_record, collection.national_bibliography_source").update();
	}

	Map<String, String> lookup(ImportReport report) throws Exception {
		return NationalBibliography.lookup(dir, wanted, store, clock, report);
	}

	@Test
	void firstLookupFillsTheTableAndTheFingerprint() throws Exception {
		ImportReport report = new ImportReport();
		Map<String, String> found = lookup(report);

		assertThat(found).containsExactly(
				Map.entry(NationalBibliography.keyOf("작별하지 않는다", "한강", "문학동네"), "9788954682152"));
		assertThat(report.nationalBibliographyReused).isFalse();
		assertThat(report.nationalBibliographyRecords).isEqualTo(5);
		assertThat(jdbc.sql("SELECT isbn13 || ' | ' || title_raw FROM collection.national_bibliography_record ORDER BY id")
			.query(String.class)
			.list()).containsExactly("9788954682152 | (장편소설) 작별하지 않는다", "9788900000009 | 모호한 책",
					"9788900000016 | 모호한 책", "9788900000023 | 두 판 \"따옴표\" 책",
					"9788900000030 | 두 판 \"따옴표\" 책");
		assertThat(jdbc.sql("SELECT source_fingerprint FROM collection.national_bibliography_source")
			.query(String.class)
			.single()).isEqualTo("book_ttl/book_0.ttl|" + Files.size(file) + "|" + MODIFIED);
	}

	/** The file keeps its name, size and time but its text is broken: only the table can give the same answer. */
	@Test
	void sameFingerprintReadsTheTableNotTheFiles() throws Exception {
		Map<String, String> fromFiles = lookup(new ImportReport());
		byte[] broken = new byte[(int) Files.size(file)];
		java.util.Arrays.fill(broken, (byte) 'x');
		Files.write(file, broken);
		Files.setLastModifiedTime(file, FileTime.from(MODIFIED));

		ImportReport report = new ImportReport();
		Map<String, String> fromTable = lookup(report);

		assertThat(report.nationalBibliographyReused).isTrue();
		assertThat(fromTable).isEqualTo(fromFiles);
		assertThat(report.nationalBibliographyRecords).isEqualTo(5);
	}

	@Test
	void changedFileIsReadAgain() throws Exception {
		lookup(new ImportReport());
		Files.writeString(file, TTL.replace("\"9788900000016\"", "\"9788900000092\""), StandardCharsets.UTF_8);
		Files.setLastModifiedTime(file, FileTime.from(MODIFIED));
		Files.writeString(file, Files.readString(file) + "\n", StandardCharsets.UTF_8);  // size differs
		Files.setLastModifiedTime(file, FileTime.from(MODIFIED));

		ImportReport report = new ImportReport();
		lookup(report);

		assertThat(report.nationalBibliographyReused).isFalse();
		assertThat(jdbc.sql("SELECT count(*) FROM collection.national_bibliography_record WHERE isbn13 = '9788900000092'")
			.query(Long.class)
			.single()).isEqualTo(1);
	}

	@Test
	void stoppedRewriteLeavesNoFingerprint() throws Exception {
		lookup(new ImportReport());
		assertThatThrownBy(() -> store.rewrite("other", clock.instant(), writer -> {
			writer.add(new NationalBibliographyStore.Raw("9788900000047", "책", "가", "나"));
			throw new IOException("stopped while reading the files");
		})).isInstanceOf(IOException.class);

		assertThat(jdbc.sql("SELECT count(*) FROM collection.national_bibliography_source").query(Long.class).single())
			.isZero();
		ImportReport report = new ImportReport();
		lookup(report);
		assertThat(report.nationalBibliographyReused).isFalse();
	}

}
