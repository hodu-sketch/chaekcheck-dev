package com.chaekcheck.collection.internal.foryou;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import com.chaekcheck.picks.BookTagTransfer;

/**
 * The {@code tag-dictionary} mode against db-test (cycle 23 step 3-3): the file's codes are put in or changed, a code
 * left out becomes inactive and is counted, and a refused file changes nothing. In the test transaction.
 */
@SpringBootTest
@Transactional
class TagDictionaryImportDbTests {

	@TempDir
	Path dir;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	BookTagTransfer bookTags;

	@Test
	void putsInTheFileAndMakesLeftOutCodesInactive() throws Exception {
		TagDictionaryImport command = new TagDictionaryImport(bookTags);
		command.run(file("code\taxis\tname\tthema_code\nm01\tmood\t잔잔한\t\ns01\tsubject\t소설\tFB\n"));

		TagDictionaryImport.Report report = command
			.run(file("code\taxis\tname\tthema_code\ns01\tsubject\t장편소설\tFBA\na01\taudience\t청소년\t\n"));

		assertThat(report.codes()).isEqualTo(2);
		assertThat(report.madeInactive()).isEqualTo(1);
		assertThat(dictionary()).containsExactly("a01:audience:청소년::true", "m01:mood:잔잔한::false",
				"s01:subject:장편소설:FBA:true");
	}

	@Test
	void aRefusedFileChangesNothing() throws Exception {
		TagDictionaryImport command = new TagDictionaryImport(bookTags);
		command.run(file("code\taxis\tname\tthema_code\nm01\tmood\t잔잔한\t\n"));

		assertThatThrownBy(() -> command.run(file("code\taxis\tname\tthema_code\ns01\tsubject\t소설\t\nbad-code\tmood\t이름\t\n")))
			.isInstanceOf(TagDictionaryFile.RefusedException.class);
		assertThat(dictionary()).containsExactly("m01:mood:잔잔한::true");
	}

	private List<String> dictionary() {
		return jdbc.sql("""
				SELECT code || ':' || axis || ':' || name || ':' || coalesce(thema_code, '') || ':' || active
				FROM picks.tag_dictionary ORDER BY code""")
			.query(String.class)
			.list();
	}

	private Path file(String text) throws Exception {
		Path file = Files.createTempFile(dir, "tag-dictionary", ".tsv");
		Files.writeString(file, text, StandardCharsets.UTF_8);
		return file;
	}

}
