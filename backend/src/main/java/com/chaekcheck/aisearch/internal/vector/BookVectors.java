package com.chaekcheck.aisearch.internal.vector;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.chaekcheck.aisearch.internal.config.AiSearchProperties;

/**
 * The book vectors of {@code book-vectors.bin}, searched for the nearest books. A book may have several rows (the two
 * layers of user 2026-10-30: its summary and the pieces of its intro, docs/aisearch/reports/2026-10-29-intro-text.md);
 * a book's score is then its best row's, and each book comes once.
 */
interface BookVectors extends AutoCloseable {

	/** Rows in the file (books times the rows a book has). */
	int count();

	/** The most rows one book has (1 when each book has one). */
	int rowsPerBook();

	int dim();

	/** The {@code limit} ids nearest {@code query} (a normalised vector of {@link #dim()}), nearest first. */
	List<Long> nearest(float[] query, int limit);

	@Override
	default void close() {
	}

	/** The most times one id appears in {@code ids}. */
	static int rowsPerBook(long[] ids) {
		long[] sorted = ids.clone();
		Arrays.sort(sorted);
		int most = sorted.length == 0 ? 0 : 1;
		for (int i = 1, run = 1; i < sorted.length; i++) {
			run = sorted[i] == sorted[i - 1] ? run + 1 : 1;
			most = Math.max(most, run);
		}
		return most;
	}

	/** The ids of the first {@code limit} books of {@code rows} (row indexes, best first), each book once. */
	static List<Long> books(long[] ids, int[] rows, int limit) {
		List<Long> out = new ArrayList<>(Math.min(limit, rows.length));
		Set<Long> seen = new HashSet<>();
		for (int i = 0; i < rows.length && out.size() < limit; i++) {
			long id = ids[rows[i]];
			if (seen.add(id)) {
				out.add(id);
			}
		}
		return out;
	}

	/** The file opened the way {@code search} says. */
	static BookVectors open(Path file, AiSearchProperties.Search search) throws IOException {
		return switch (search.mode()) {
			case EXACT -> BookVectorFile.read(file);
			case FUNNEL -> BookVectorFunnel.open(file, search.hammingKeep(), search.binaryKeep());
		};
	}

}
