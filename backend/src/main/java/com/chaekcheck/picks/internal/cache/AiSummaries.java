package com.chaekcheck.picks.internal.cache;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * The short AI-written summaries of the picked books (user 2026-10-19: "추천도서의 모든 책들 ai 추천 요약글 한두줄"),
 * from {@code picks/ai-summaries.tsv}: {@code list}, {@code isbn13}, {@code title}, {@code summary}, tab separated,
 * a header line first. A row is found by its ISBN, else by its list and the list's title; a book without one shows
 * none. The file is written by hand-checked AI output (docs/maintenance/4-picks.md 8절) and ships with the build.
 */
final class AiSummaries {

	static final String RESOURCE = "picks/ai-summaries.tsv";

	static final AiSummaries DEFAULT = load(AiSummaries.class.getClassLoader().getResourceAsStream(RESOURCE));

	private final Map<String, String> byIsbn;

	private final Map<String, String> byTitle;

	private AiSummaries(Map<String, String> byIsbn, Map<String, String> byTitle) {
		this.byIsbn = byIsbn;
		this.byTitle = byTitle;
	}

	/** The summary of one stored row, or null. */
	String of(String list, String isbn13, String title) {
		if (isbn13 != null) {
			String found = byIsbn.get(isbn13);
			if (found != null) {
				return found;
			}
		}
		return byTitle.get(list + "\t" + title);
	}

	/** Reads the file; none (an empty set) when the build has no file. */
	static AiSummaries load(InputStream in) {
		Map<String, String> byIsbn = new HashMap<>();
		Map<String, String> byTitle = new HashMap<>();
		if (in == null) {
			return new AiSummaries(byIsbn, byTitle);
		}
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
			String line = reader.readLine();
			while ((line = reader.readLine()) != null) {
				String[] cells = line.split("\t", -1);
				if (cells.length < 4 || cells[3].isBlank()) {
					continue;
				}
				String summary = cells[3].strip();
				if (!cells[1].isBlank()) {
					byIsbn.putIfAbsent(cells[1].strip(), summary);
				}
				byTitle.putIfAbsent(cells[0].strip() + "\t" + cells[2].strip(), summary);
			}
		}
		catch (IOException ex) {
			throw new UncheckedIOException("Cannot read " + RESOURCE, ex);
		}
		return new AiSummaries(Map.copyOf(byIsbn), Map.copyOf(byTitle));
	}

}
