package com.chaekcheck.picks.internal.cache;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * One of our books for a recommended book when several match (ADR-0125, plan scope decision #7) [확정]: the edition
 * held most, and of a set its first volume.
 * <ul>
 * <li>Held = the enabled libraries that do not lend by subscription, plus 1 when any enabled subscription library has
 * it; libraries that are not enabled count for nothing.</li>
 * <li>A set is one edition: books with the same title without edition marks and volume ({@link ShownTitle}) and the
 * same publisher, only the volume differing (the same author too: every candidate comes from one lookup by ISBN or by
 * title and first author). A set is held as much as its volume held most.</li>
 * <li>The edition held most wins; of a set the first volume ({@code 1}, {@code (상)}, else the smallest) is used
 * (「토지」: volume 2 held by 209 and volume 1 by 207 still gives volume 1).</li>
 * <li>The same count: the smaller book id.</li>
 * </ul>
 * The collector (scripts/crawl/recommended_lists.py) chooses the other lists' fixed editions by the same rule; both read
 * scripts/crawl/testdata/book_choice_cases.json. Rule: docs/maintenance/rules-index.md "판 고르기"
 */
public final class BookChoice {

	/**
	 * Our book found for a recommended book.
	 * @param title {@code catalog.book.title}, for the set and its first volume
	 * @param publisherNorm {@code catalog.book.publisher_norm}, for the set; null when unknown
	 */
	public record Candidate(long bookId, String title, String publisherNorm, long[] libraryIds) {
	}

	/** One edition: a single book, or every volume of a set. */
	private record Edition(int held, Candidate shown) {
	}

	private BookChoice() {
	}

	/**
	 * @param lendingKinds every enabled library id with whether it lends by subscription
	 * ({@link com.chaekcheck.library.LibraryCatalog#lendingKinds()})
	 */
	public static Optional<Candidate> choose(Collection<Candidate> candidates, Map<Long, Boolean> lendingKinds) {
		Map<String, List<Candidate>> sets = new LinkedHashMap<>();
		List<Edition> editions = new ArrayList<>();
		for (Candidate candidate : candidates) {
			ShownTitle.Reading reading = ShownTitle.read(Objects.requireNonNullElse(candidate.title(), ""));
			if (reading.volume() == null) {
				editions.add(new Edition(held(candidate.libraryIds(), lendingKinds), candidate));
			}
			else {
				sets.computeIfAbsent(key(reading.base()) + "\u0000" + Objects.requireNonNullElse(candidate.publisherNorm(), ""),
						k -> new ArrayList<>())
					.add(candidate);
			}
		}
		for (List<Candidate> set : sets.values()) {
			int held = set.stream().mapToInt(c -> held(c.libraryIds(), lendingKinds)).max().orElse(0);
			editions.add(new Edition(held, set.stream().min(FIRST_VOLUME).orElseThrow()));
		}
		return editions.stream()
			.min(Comparator.comparingInt(Edition::held).reversed().thenComparingLong(e -> e.shown().bookId()))
			.map(Edition::shown);
	}

	/** The first volume of a set: 1 or (상), else the smallest, then the smaller id. */
	private static final Comparator<Candidate> FIRST_VOLUME = Comparator
		.comparingInt((Candidate c) -> volumeOrder(ShownTitle.read(Objects.requireNonNullElse(c.title(), "")).volume()))
		.thenComparingLong(Candidate::bookId);

	/** 상 = 1, 중 = 2, 하 = 3, a number as itself, 「2부 3권」 = 203 (the collector's volume_order). */
	private static int volumeOrder(String volume) {
		return switch (volume) {
			case "상" -> 1;
			case "중" -> 2;
			case "하" -> 3;
			default -> volume.contains("부 ")
					? Integer.parseInt(volume.substring(0, volume.indexOf('부'))) * 100
							+ Integer.parseInt(volume.substring(volume.indexOf(' ') + 1, volume.length() - 1))
					: Integer.parseInt(volume);
		};
	}

	private static String key(String base) {
		return base.toLowerCase(Locale.ROOT).replaceAll("[\\s\\p{Punct}·ㆍ]+", "");
	}

	static int held(long[] libraryIds, Map<Long, Boolean> lendingKinds) {
		int owned = 0;
		boolean subscription = false;
		for (long id : libraryIds) {
			Boolean kind = lendingKinds.get(id);
			if (kind == null) {
				continue;
			}
			if (kind) {
				subscription = true;
			}
			else {
				owned++;
			}
		}
		return owned + (subscription ? 1 : 0);
	}

}
