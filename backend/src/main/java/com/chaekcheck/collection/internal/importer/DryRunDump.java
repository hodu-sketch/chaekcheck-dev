package com.chaekcheck.collection.internal.importer;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.zip.GZIPOutputStream;

import com.chaekcheck.collection.internal.importer.CollectedCatalog.Row;

/**
 * A dry run's result as files (cycle 11 handoff step 8, ADR-0079), so two ways of grouping can be compared by
 * {@code scripts/catalog/compare_grouping.py}. Tab separated, gzip, UTF-8, tabs and line breaks in values written as
 * spaces:
 * <ul>
 * <li>{@code books.tsv.gz}: book number (1, 2, ... in the order books are given), title, author, publisher, published
 * date, ISBN, content type, libraries (comma separated)</li>
 * <li>{@code rows.tsv.gz}: library, item key, book number, for every row of the second pass</li>
 * </ul>
 */
final class DryRunDump {

	/** Reads the rows of the second pass. */
	@FunctionalInterface
	interface Rows {

		void scan(Consumer<Row> sink) throws SQLException;

	}

	private DryRunDump() {
	}

	static void write(Path folder, BookGrouper grouping, Rows rows) throws IOException, SQLException {
		Files.createDirectories(folder);
		try (Writer books = gzip(folder.resolve("books.tsv.gz"))) {
			long[] next = { 0 };
			grouping.forEachBook(book -> {
				long number = ++next[0];
				line(books, Long.toString(number), book.title(), book.authorDisplay(), book.publisher(),
						book.publishedOn() == null ? null : book.publishedOn().toString(), book.isbn13(),
						book.contentType(),
						Arrays.stream(book.libraryIds()).mapToObj(Long::toString).collect(Collectors.joining(",")));
				return number;
			});
		}
		try (Writer out = gzip(folder.resolve("rows.tsv.gz"))) {
			rows.scan(row -> line(out, Long.toString(row.library().serviceId()), row.itemKey(),
					Long.toString(grouping.bookOf(row))));
		}
	}

	private static Writer gzip(Path file) throws IOException {
		return new BufferedWriter(new OutputStreamWriter(new GZIPOutputStream(Files.newOutputStream(file), 1 << 16),
				StandardCharsets.UTF_8), 1 << 16);
	}

	private static void line(Writer out, String... values) {
		try {
			for (int i = 0; i < values.length; i++) {
				if (i > 0) {
					out.write('\t');
				}
				if (values[i] != null) {
					out.write(values[i].replace('\t', ' ').replace('\n', ' ').replace('\r', ' '));
				}
			}
			out.write('\n');
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

}
