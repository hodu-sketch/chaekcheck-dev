package com.chaekcheck.collection.internal.booktexts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.chaekcheck.catalog.BookProfiles.Profile;
import com.chaekcheck.catalog.BookTextsTransfer.BookProfile;

/** The {@code book-profiles} rows (cycle 26 step 5): nine cells, empty cells are no value, any problem refuses all. */
class BookProfilesFileTests {

	private static final String EXPORT = "# export_id=0b8f2c64-5a1e-4d6f-9c1b-7d2e3f4a5b6c";

	@Test
	void readsTheNineCells() {
		List<BookProfile> rows = BookProfilesImport.parse(List.of(EXPORT,
				"11\t문학\t한국소설\t0\t성장|가족\t한국\t성인\t보통\t장편|연작\r", "12\t\t\t\t\t\t\t\t"));

		assertThat(rows).containsExactly(
				new BookProfile(11, new Profile("문학", "한국소설", false, List.of("성장", "가족"), "한국", "성인", "보통",
						List.of("장편", "연작"))),
				new BookProfile(12, new Profile(null, null, null, List.of(), null, null, null, List.of())));
	}

	@Test
	void everyProblemIsNamedWithItsRowAndNothingIsKept() {
		assertThatThrownBy(() -> BookProfilesImport.parse(List.of(EXPORT, "11\t문학", "x\t\t\t\t\t\t\t\t",
				"12\t\t\tyes\t\t\t\t\t", "13\t\t\t\t\t\t\t\t", "13\t\t\t\t\t\t\t\t", "0\t\t\t\t\t\t\t\t", "")))
			.isInstanceOf(BookTextsFile.RefusedException.class)
			.hasMessageContaining("6 problem(s)")
			.hasMessageContaining("row 2: 2 cells")
			.hasMessageContaining("row 3: the book id")
			.hasMessageContaining("row 4: fiction")
			.hasMessageContaining("row 6: a second row for book 13")
			.hasMessageContaining("row 7: a book id must be 1 or more")
			.hasMessageContaining("row 8: a blank row");
		assertThatThrownBy(() -> BookProfilesImport.parse(List.of(EXPORT))).isInstanceOf(BookTextsFile.RefusedException.class)
			.hasMessageContaining("no profile row");
	}

}
