package com.chaekcheck.collection.internal.booktexts;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.chaekcheck.catalog.BookProfiles.Profile;
import com.chaekcheck.catalog.BookTextsTransfer;
import com.chaekcheck.catalog.BookTextsTransfer.BookProfile;

/**
 * The {@code book-profiles} import mode (cycle 26, ADR-0165): the PC's {@code book-profiles.tsv} into
 * {@code catalog.book_profile}, replacing every profile in one transaction. The file: UTF-8, first row
 * {@code # export_id=<uuid>} ({@link ExportIds}: another export is refused unless {@code force}), then one book a row
 * with nine tab-separated cells and no header: id, field, subfield, fiction ({@code 1}, {@code 0} or empty), genres
 * joined by {@code |}, country, audience, level, forms joined by {@code |}. An empty cell is no value. Any problem
 * refuses the whole file with the row numbers, before anything is written. The AI search reads the profiles at start,
 * so a running server sees them after a restart.
 */
public final class BookProfilesImport {

	private static final Logger log = LoggerFactory.getLogger(BookProfilesImport.class);

	private static final int COLUMNS = 9;

	/** At most this many problems are named; the count says how many there were. */
	private static final int PROBLEMS_SHOWN = 20;

	private final BookTextsTransfer transfer;

	private final Supplier<Optional<UUID>> latestExportId;

	/** @param latestExportId this database's latest succeeded catalog import's export id ({@link ExportIds#latest}) */
	public BookProfilesImport(BookTextsTransfer transfer, Supplier<Optional<UUID>> latestExportId) {
		this.transfer = transfer;
		this.latestExportId = latestExportId;
	}

	/** @param forced whether the file's export id differed and {@code force} let it through */
	public record Report(UUID exportId, int profiles, boolean forced) {
	}

	/** @throws BookTextsFile.RefusedException when the file is refused; nothing is written */
	public Report run(Path file, boolean force) {
		List<String> rows;
		try {
			rows = Files.readAllLines(file, StandardCharsets.UTF_8);
		}
		catch (IOException e) {
			throw new UncheckedIOException("Could not read the book-profiles file " + file + " as UTF-8", e);
		}
		UUID exportId = ExportIds.parse(rows.isEmpty() ? null : rows.getFirst());
		List<BookProfile> profiles = parse(rows);
		boolean forced = ExportIds.check(latestExportId.get(), exportId, force, "book-profiles");
		int written = transfer.replaceProfiles(profiles);
		log.info("book-profiles: {} profiles from export {}{}; restart the server for the AI search to read them",
				written, exportId, forced ? " (forced)" : "");
		return new Report(exportId, written, forced);
	}

	/** The rows after the first. */
	static List<BookProfile> parse(List<String> rows) {
		List<String> problems = new ArrayList<>();
		List<BookProfile> profiles = new ArrayList<>();
		Set<Long> seen = new HashSet<>();
		for (int i = 1; i < rows.size(); i++) {
			int rowNo = i + 1;
			String row = rows.get(i).endsWith("\r") ? rows.get(i).substring(0, rows.get(i).length() - 1) : rows.get(i);
			if (row.isBlank()) {
				problems.add("row " + rowNo + ": a blank row");
				continue;
			}
			String[] cell = row.split("\t", -1);
			if (cell.length != COLUMNS) {
				problems.add("row " + rowNo + ": " + cell.length + " cells, not " + COLUMNS);
				continue;
			}
			long bookId;
			try {
				bookId = Long.parseLong(cell[0]);
			}
			catch (NumberFormatException e) {
				problems.add("row " + rowNo + ": the book id is not a number: " + cell[0]);
				continue;
			}
			Boolean fiction = switch (cell[3]) {
				case "1" -> Boolean.TRUE;
				case "0" -> Boolean.FALSE;
				case "" -> null;
				default -> {
					problems.add("row " + rowNo + ": fiction must be 1, 0 or empty: " + cell[3]);
					yield null;
				}
			};
			if (!seen.add(bookId)) {
				problems.add("row " + rowNo + ": a second row for book " + bookId);
				continue;
			}
			try {
				profiles.add(new BookProfile(bookId, new Profile(text(cell[1]), text(cell[2]), fiction, list(cell[4]),
						text(cell[5]), text(cell[6]), text(cell[7]), list(cell[8]))));
			}
			catch (IllegalArgumentException e) {
				problems.add("row " + rowNo + ": " + e.getMessage());
			}
		}
		if (profiles.isEmpty() && problems.isEmpty()) {
			problems.add("no profile row after the export id");
		}
		if (!problems.isEmpty()) {
			throw new BookTextsFile.RefusedException("The book-profiles file was refused, " + problems.size()
					+ " problem(s), nothing put in:\n"
					+ String.join("\n", problems.subList(0, Math.min(PROBLEMS_SHOWN, problems.size()))));
		}
		return profiles;
	}

	private static String text(String cell) {
		return cell.isEmpty() ? null : cell;
	}

	private static List<String> list(String cell) {
		return cell.isEmpty() ? List.of() : Arrays.asList(cell.split("\\|"));
	}

}
