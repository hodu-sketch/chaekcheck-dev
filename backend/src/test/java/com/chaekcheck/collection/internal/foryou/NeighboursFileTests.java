package com.chaekcheck.collection.internal.foryou;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.chaekcheck.foryou.ForYouTransfer.Neighbours;

/**
 * {@link NeighboursFile} (cycle 23 step 3-3): the export id row, the neighbour rules with row numbers, each book once,
 * no book row, and the rows read one by one for the load.
 */
class NeighboursFileTests {

	static final UUID EXPORT = UUID.fromString("11111111-2222-3333-4444-555555555555");

	@TempDir
	Path dir;

	@Test
	void readsTheExportIdAndTheRows() throws Exception {
		Path file = write("﻿# export_id=" + EXPORT + "\r\n1\t2,3,4\r\n2\t1\r\n");

		assertThat(NeighboursFile.exportId(file)).isEqualTo(EXPORT);
		assertThat(NeighboursFile.check(file)).isEqualTo(2);
		List<Neighbours> rows = new ArrayList<>();
		long count = NeighboursFile.load(file, it -> {
			it.forEachRemaining(rows::add);
			return rows.size();
		});
		assertThat(count).isEqualTo(2);
		assertThat(rows).containsExactly(new Neighbours(1, new int[] { 2, 3, 4 }), new Neighbours(2, new int[] { 1 }));
	}

	@Test
	void aMissingOrWrongExportIdRowRefusesTheFile() throws Exception {
		assertThatThrownBy(() -> NeighboursFile.exportId(write("1\t2\n"))).isInstanceOf(NeighboursFile.RefusedException.class)
			.hasMessageContaining("# export_id=");
		assertThatThrownBy(() -> NeighboursFile.exportId(write("# export_id=42\n1\t2\n")))
			.isInstanceOf(NeighboursFile.RefusedException.class)
			.hasMessageContaining("not a UUID");
		assertThatThrownBy(() -> NeighboursFile.exportId(write(""))).isInstanceOf(NeighboursFile.RefusedException.class);
	}

	@Test
	void everyBrokenRuleIsNamedWithItsRow() throws Exception {
		String twentyOne = String.join(",", IntStream.rangeClosed(2, 22).mapToObj(String::valueOf).toList());
		Path file = write("# export_id=" + EXPORT + "\n" + "1\t2,3\n" + "2\t2\n" + "3\t4,4\n" + "4\t" + twentyOne + "\n"
				+ "5\t\n" + "6\t2147483648\n" + "2147483648\t1\n" + "x\t1\n" + "7\t1\t2\n" + "\n" + "1\t5\n" + "8\t0\n");

		assertThatThrownBy(() -> NeighboursFile.check(file)).isInstanceOf(NeighboursFile.RefusedException.class)
			.hasMessageContaining("11 problem(s)")
			.hasMessageContaining("row 3: book 2 is its own neighbour")
			.hasMessageContaining("row 4: book 3 has neighbour 4 twice")
			.hasMessageContaining("row 5: book 4 has 21 neighbours")
			.hasMessageContaining("row 6: book 5: a neighbour id is not a number")
			.hasMessageContaining("row 7: book 6: a neighbour id is not a number up to 2147483647")
			.hasMessageContaining("row 8: book id out of range")
			.hasMessageContaining("row 9: book_id is not a number")
			.hasMessageContaining("row 10: 3 columns")
			.hasMessageContaining("row 11: a blank row")
			.hasMessageContaining("row 12: book 1 comes twice")
			.hasMessageContaining("row 13: book 8 has a neighbour id 0");
	}

	@Test
	void aFileWithoutBookRowsIsRefused() throws Exception {
		assertThatThrownBy(() -> NeighboursFile.check(write("# export_id=" + EXPORT + "\n")))
			.isInstanceOf(NeighboursFile.RefusedException.class)
			.hasMessageContaining("no book row");
	}

	private Path write(String text) throws Exception {
		Path file = Files.createTempFile(dir, "neighbours", ".tsv");
		Files.writeString(file, text, StandardCharsets.UTF_8);
		return file;
	}

}
