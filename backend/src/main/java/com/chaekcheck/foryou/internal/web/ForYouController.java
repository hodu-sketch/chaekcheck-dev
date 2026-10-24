package com.chaekcheck.foryou.internal.web;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.chaekcheck.catalog.BookCards;
import com.chaekcheck.catalog.BookCards.BookCard;
import com.chaekcheck.foryou.internal.ForYouSnapshot;
import com.chaekcheck.foryou.internal.build.BaseOrder.Entry;
import com.chaekcheck.foryou.internal.listed.ListedSource;
import com.chaekcheck.foryou.internal.listed.ListedSource.ListedRef;
import com.chaekcheck.foryou.internal.store.BookTexts;
import com.chaekcheck.foryou.internal.store.ForYouStore.LastBuild;
import com.chaekcheck.library.LibraryCatalog;
import com.chaekcheck.library.PlanOptions;
import com.chaekcheck.library.SearchTarget;
import com.chaekcheck.picks.BookTagTransfer.Tag;

/**
 * The 「나에게 맞는 책」 tab (cycle 23 design-delta "API"). No login, GET only; no library site is asked. Only books
 * that at least one of the plan's libraries holds are sent ({@code planLibraryCount} ≥ 1, AC-5). Scores and source
 * names are never sent (R3-2). The browser sends only the base books (≤ 3) and the books to show again (≤ 6); what it
 * saw stays in the browser (R5-1).
 */
@RestController
class ForYouController {

	static final int MAX_SIMILAR = 60;

	private final ForYouSnapshot snapshot;

	private final BookCards bookCards;

	private final BookTexts bookTexts;

	private final ListedSource listedSource;

	private final LibraryCatalog libraryCatalog;

	ForYouController(ForYouSnapshot snapshot, BookCards bookCards, BookTexts bookTexts, ListedSource listedSource,
			LibraryCatalog libraryCatalog) {
		this.snapshot = snapshot;
		this.bookCards = bookCards;
		this.bookTexts = bookTexts;
		this.listedSource = listedSource;
		this.libraryCatalog = libraryCatalog;
	}

	/**
	 * Today's base list (the last succeeded build) in its order. Before any build succeeded, {@code builtOn} is null
	 * and the listed books come by how many libraries hold them [안].
	 */
	@GetMapping("/api/v1/for-you/books")
	BaseBooks books(@RequestParam MultiValueMap<String, String> params) {
		Set<Long> planIds = planIds(new ForYouParams(params));
		Map<Long, ListedRef> listed = listed();
		LastBuild last = snapshot.current().orElse(null);
		List<Long> ids;
		if (last != null) {
			ids = last.entries().stream().map(Entry::bookId).toList();
		}
		else {
			ids = listed.values()
				.stream()
				.sorted(Comparator.<ListedRef>comparingInt(ref -> ref.libraryIds().length).reversed()
					.thenComparingLong(ListedRef::bookId))
				.map(ListedRef::bookId)
				.toList();
		}
		return new BaseBooks(last == null ? null : last.builtOn(), cards(ids, planIds, listed));
	}

	/**
	 * The neighbours of the base books (≤ 20 each), without the base books and books outside the plan: books with a
	 * score in the last succeeded build first, by score, then by plan libraries, then by id; at most 60. Unknown ids are
	 * left out quietly.
	 */
	@GetMapping("/api/v1/for-you/similar-books")
	SimilarBooks similarBooks(@RequestParam MultiValueMap<String, String> params) {
		ForYouParams read = new ForYouParams(params);
		List<Long> base = read.baseBookIds();
		List<Long> reshow = read.reshowBookIds();
		Set<Long> planIds = planIds(read);
		Map<Long, int[]> neighbours = bookTexts.neighbours(base);
		Set<Long> candidates = new LinkedHashSet<>();
		for (long bookId : base) {
			for (int neighbour : neighbours.getOrDefault(bookId, new int[0])) {
				candidates.add((long) neighbour);
			}
		}
		base.forEach(candidates::remove);
		Map<Long, ListedRef> listed = listed();
		Map<Long, Double> scores = snapshot.current().map(LastBuild::scores).orElse(Map.of());
		List<Card> books = new ArrayList<>(cards(List.copyOf(candidates), planIds, listed));
		books.sort(Comparator.<Card>comparingInt(card -> scores.containsKey(card.bookId()) ? 0 : 1)
			.thenComparing(card -> -scores.getOrDefault(card.bookId(), 0.0))
			.thenComparing(card -> -card.planLibraryCount())
			.thenComparingLong(Card::bookId));
		Map<Long, String> titles = new HashMap<>();
		bookCards.cards(base).forEach(card -> titles.put(card.bookId(), card.title()));
		List<BaseBook> baseBooks = base.stream()
			.filter(titles::containsKey)
			.map(id -> new BaseBook(id, titles.get(id)))
			.toList();
		List<Long> shownAgain = reshow.stream().filter(id -> !base.contains(id)).toList();
		return new SimilarBooks(baseBooks, books.subList(0, Math.min(MAX_SIMILAR, books.size())),
				cards(shownAgain, planIds, listed));
	}

	/** Cards in the given order, books outside the plan and unknown books left out. */
	private List<Card> cards(List<Long> ids, Set<Long> planIds, Map<Long, ListedRef> listed) {
		Map<Long, BookCard> found = new HashMap<>();
		bookCards.cards(ids).forEach(card -> found.put(card.bookId(), card));
		Map<Long, String> blurbs = bookTexts.blurbs(found.keySet());
		Map<Long, String> covers = bookTexts.coverKeys(found.keySet());
		List<Card> cards = new ArrayList<>(ids.size());
		Set<Long> added = new HashSet<>();
		for (long id : ids) {
			BookCard card = found.get(id);
			if (card == null || !added.add(id)) {
				continue;
			}
			int planCount = 0;
			for (long libraryId : card.libraryIds()) {
				if (planIds.contains(libraryId)) {
					planCount++;
				}
			}
			if (planCount == 0) {
				continue;
			}
			ListedRef ref = listed.get(id);
			String cover = covers.get(id);
			cards.add(new Card(id, card.title(), card.author(), card.publisher(),
					cover == null ? null : "/covers/" + cover + ".jpg", blurbs.get(id),
					ref == null ? List.of() : ref.tags(), planCount, ref == null ? List.of() : ref.listKeys(),
					card.authorKey()));
		}
		return cards;
	}

	private Map<Long, ListedRef> listed() {
		Map<Long, ListedRef> listed = new HashMap<>();
		listedSource.listed().forEach(ref -> listed.put(ref.bookId(), ref));
		return listed;
	}

	/** The ids of the libraries the plan in the parameters searches (as the picks API counts them). */
	private Set<Long> planIds(ForYouParams read) {
		PlanOptions options = read.planOptions();
		Set<Long> residence = read.regionIds("residenceRegionIds");
		Set<Long> work = read.regionIds("workRegionIds");
		Set<Long> planIds = new HashSet<>();
		for (SearchTarget target : libraryCatalog.planSearch(residence, work, options)) {
			planIds.add(target.id());
		}
		return planIds;
	}

	/** @param builtOn the build's day (Asia/Seoul), null before the first build */
	record BaseBooks(LocalDate builtOn, List<Card> books) {
	}

	record SimilarBooks(List<BaseBook> baseBooks, List<Card> books, List<Card> reshowBooks) {
	}

	record BaseBook(long bookId, String title) {
	}

	/**
	 * One card (design-delta "카드 칸").
	 * @param coverUrl {@code /covers/<file_key>.jpg}, or null for the title-letter cover
	 * @param blurb the tab blurb (R8), or null
	 * @param tags 2-3 tags of a listed book, empty otherwise
	 * @param planLibraryCount how many of the plan's libraries hold the book (≥ 1)
	 * @param lists the recommended lists the book is on (keys only; the web writes the tab names)
	 * @param authorKey the first author's {@code name_exact}, for "no three of one author in a row" (AC-8), or null
	 */
	record Card(long bookId, String title, String author, String publisher, String coverUrl, String blurb,
			List<Tag> tags, int planLibraryCount, List<String> lists, String authorKey) {
	}

}
