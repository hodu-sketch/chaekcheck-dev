package com.chaekcheck.collection.internal.foryou;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.chaekcheck.catalog.BookTextsTransfer.DictionaryRow;

/** {@link TagDictionaryFile} (cycle 23 step 3-3): the header, the table's rules with row numbers, no empty file. */
class TagDictionaryFileTests {

	static final String HEADER = "code\taxis\tname\tthema_code";

	@Test
	void readsTheRowsWithAnEmptyThemaCodeAsNone() {
		assertThat(TagDictionaryFile.parse(List.of("﻿" + HEADER + "\r", "m01\tmood\t잔잔한\t\r",
				"s01\tsubject\t장편소설\tFBA")))
			.containsExactly(new DictionaryRow("m01", "mood", "잔잔한", null),
					new DictionaryRow("s01", "subject", "장편소설", "FBA"));
	}

	@Test
	void everyBadRowIsNamedWithItsRow() {
		assertThatThrownBy(() -> TagDictionaryFile.parse(List.of(HEADER, "m-1\tmood\t잔잔한\t", "m02\tgenre\t이름\t",
				"m03\tmood\t \t", "m04\tmood\t이름", "", "s01\tsubject\t소설\t", "s01\tsubject\t소설\t")))
			.isInstanceOf(TagDictionaryFile.RefusedException.class)
			.hasMessageContaining("6 problem(s)")
			.hasMessageContaining("row 2: not a tag code")
			.hasMessageContaining("row 3: axis")
			.hasMessageContaining("row 4: code m03 has no name")
			.hasMessageContaining("row 5: 3 columns")
			.hasMessageContaining("row 6: a blank row")
			.hasMessageContaining("row 8: code s01 comes twice");
	}

	@Test
	void aWrongHeaderOrNoTagRefusesTheFile() {
		assertThatThrownBy(() -> TagDictionaryFile.parse(List.of("code\taxis\tname", "m01\tmood\t잔잔한")))
			.isInstanceOf(TagDictionaryFile.RefusedException.class)
			.hasMessageContaining("header");
		// every code would be made inactive
		assertThatThrownBy(() -> TagDictionaryFile.parse(List.of(HEADER)))
			.isInstanceOf(TagDictionaryFile.RefusedException.class)
			.hasMessageContaining("no tag");
	}

}
