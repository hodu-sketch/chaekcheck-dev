package com.chaekcheck.collection.internal.importer;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;

import com.chaekcheck.catalog.CatalogLoad.Baseline;
import com.chaekcheck.library.LibraryCatalog;
import com.chaekcheck.library.LibraryRef;

/**
 * What an export file and the database it is applied to must share besides the catalog (cycle 11, ADR-0078): the
 * enabled libraries and the collection links. An import maps collection ids to service libraries through both, so a
 * file made with other libraries or links would put holdings on the wrong library.
 * @param libraryCount enabled libraries
 * @param libraryHash SHA-256 (hex) of {@code id\tbaseUrl\tplatformType\n} per enabled library, in id order
 * @param linkCount rows of {@code collection.collection_link}
 * @param linkHash SHA-256 (hex) of {@code collection_library_id\tlibrary_id\n} per link, in that order
 */
record DatabaseFingerprint(int libraryCount, String libraryHash, int linkCount, String linkHash) {

	static DatabaseFingerprint of(LibraryCatalog libraries, JdbcClient jdbc) {
		StringBuilder text = new StringBuilder();
		List<LibraryRef> refs = libraries.enabledLibraryRefs()
			.stream()
			.sorted(Comparator.comparingLong(LibraryRef::id))
			.toList();
		for (LibraryRef ref : refs) {
			text.append(ref.id()).append('\t').append(ref.baseUrl()).append('\t').append(ref.platformType()).append('\n');
		}
		String libraryHash = sha256(text.toString());
		List<String> links = jdbc.sql("""
				SELECT collection_library_id || E'\\t' || library_id FROM collection.collection_link
				ORDER BY collection_library_id, library_id""").query(String.class).list();
		return new DatabaseFingerprint(refs.size(), libraryHash, links.size(),
				sha256(links.stream().map(line -> line + "\n").reduce("", String::concat)));
	}

	/**
	 * What differs, for the {@code mismatch} row's {@code failure_detail}: "도서관", "연결표", "책 표 <value>" and
	 * "표 구조", each with this database's and the file's values. Empty when everything is the same.
	 */
	static List<String> differences(DatabaseFingerprint here, Baseline hereBaseline, DatabaseFingerprint file,
			Baseline fileBaseline) {
		List<String> found = new ArrayList<>();
		if (here.libraryCount != file.libraryCount || !here.libraryHash.equals(file.libraryHash)) {
			found.add("도서관: 이 DB " + here.libraryCount + "곳 " + shortHash(here.libraryHash) + " / 파일 "
					+ file.libraryCount + "곳 " + shortHash(file.libraryHash));
		}
		if (here.linkCount != file.linkCount || !here.linkHash.equals(file.linkHash)) {
			found.add("연결표: 이 DB " + here.linkCount + "줄 " + shortHash(here.linkHash) + " / 파일 " + file.linkCount
					+ "줄 " + shortHash(file.linkHash));
		}
		compare(found, "book 줄 수", hereBaseline.bookCount(), fileBaseline.bookCount());
		compare(found, "book 가장 큰 번호", hereBaseline.bookMaxId(), fileBaseline.bookMaxId());
		compare(found, "book 시퀀스", hereBaseline.bookSequence(), fileBaseline.bookSequence());
		compare(found, "holding 줄 수", hereBaseline.holdingCount(), fileBaseline.holdingCount());
		compare(found, "holding 가장 큰 번호", hereBaseline.holdingMaxId(), fileBaseline.holdingMaxId());
		compare(found, "holding 시퀀스", hereBaseline.holdingSequence(), fileBaseline.holdingSequence());
		if (!hereBaseline.columnsHash().equals(fileBaseline.columnsHash())) {
			found.add("표 구조: 이 DB " + shortHash(hereBaseline.columnsHash()) + " / 파일 "
					+ shortHash(fileBaseline.columnsHash()));
		}
		return found;
	}

	private static void compare(List<String> found, String name, long here, long file) {
		if (here != file) {
			found.add("책 표 " + name + ": 이 DB " + here + " / 파일 " + file);
		}
	}

	private static String shortHash(String hash) {
		return hash.substring(0, Math.min(12, hash.length()));
	}

	static String sha256(String text) {
		try {
			return HexFormat.of()
				.formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

}
