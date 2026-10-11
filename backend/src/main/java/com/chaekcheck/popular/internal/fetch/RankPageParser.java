package com.chaekcheck.popular.internal.fetch;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

/**
 * Reads one ranking answer (an HTML fragment, plan research kpipa.md 2절): one {@code .book-grid-item} per book, in
 * rank order (the answer has no rank numbers). Title {@code .figcaption}, ISBN {@code .info .number} (hyphens
 * removed), sub-category {@code .info .category}, small cover {@code img} {@code src}. The author
 * ({@code .info .writer}) is not read [확정].
 */
public final class RankPageParser {

	/** Books kept per list; more are cut [안], fewer fail ({@code too_few_books}). */
	public static final int BOOKS_PER_LIST = 50;

	private static final Pattern ISBN13 = Pattern.compile("97[89][0-9]{10}");

	/**
	 * A small cover on the same site (plan cycle 10 C7 [안]): {@code /files/<folder>/.../s_<name>}, plain path
	 * characters only, no {@code ..} or {@code //}, no scheme or host. The site keeps covers in several folders
	 * ({@code isbn}, {@code onix}, {@code old}, {@code trans} in the 2026-10-12 answer), not only {@code isbn}.
	 */
	private static final Pattern COVER_PATH = Pattern.compile("/files/[a-z]+/(?:[A-Za-z0-9_.-]+/)*s_[A-Za-z0-9_.-]+");

	private RankPageParser() {
	}

	/**
	 * @param list names the list in failure details, e.g. "소설"
	 * @throws FetchFailure {@code format_changed} when there is no book item or a title is empty,
	 * {@code too_few_books} when there are fewer than 50
	 */
	public static List<RankedBook> parse(String html, String list) {
		Document doc = Jsoup.parse(html);
		List<Element> items = doc.select(".book-grid-item");
		if (items.isEmpty()) {
			throw new FetchFailure(FailureReason.FORMAT_CHANGED, list + ": no .book-grid-item");
		}
		if (items.size() < BOOKS_PER_LIST) {
			throw new FetchFailure(FailureReason.TOO_FEW_BOOKS, list + ": " + items.size() + " books");
		}
		List<RankedBook> books = new ArrayList<>(BOOKS_PER_LIST);
		for (Element item : items.subList(0, BOOKS_PER_LIST)) {
			int rank = books.size() + 1;
			String title = text(item.selectFirst(".figcaption"));
			if (title == null) {
				throw new FetchFailure(FailureReason.FORMAT_CHANGED, list + ": no title at rank " + rank);
			}
			books.add(new RankedBook(rank, isbn(text(item.selectFirst(".info .number"))), title,
					text(item.selectFirst(".info .category")), coverPath(item.selectFirst("img"))));
		}
		return List.copyOf(books);
	}

	/** Books whose ISBN is empty or not an ISBN-13. */
	public static int missingIsbnCount(List<RankedBook> books) {
		return (int) books.stream().filter(book -> book.isbn13() == null).count();
	}

	static String isbn(String raw) {
		if (raw == null) {
			return null;
		}
		String digits = raw.replace("-", "").replaceAll("\\s", "");
		return ISBN13.matcher(digits).matches() ? digits : null;
	}

	static String coverPath(Element img) {
		if (img == null) {
			return null;
		}
		String src = img.attr("src").strip();
		if (!COVER_PATH.matcher(src).matches() || src.contains("..") || src.contains("//")) {
			return null;
		}
		return src;
	}

	private static String text(Element element) {
		if (element == null) {
			return null;
		}
		String text = element.text().strip();
		return text.isEmpty() ? null : text;
	}

}
