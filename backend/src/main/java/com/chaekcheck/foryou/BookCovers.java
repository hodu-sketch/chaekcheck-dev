package com.chaekcheck.foryou;

import java.util.Collection;
import java.util.Map;

/**
 * The tab covers the crawl PC fetched (cycle 23, {@code /covers/<file_key>.jpg}), for other modules to read (user
 * 2026-10-25: the AI search cards of books off the lists showed an empty cover).
 */
public interface BookCovers {

	/**
	 * The cover path of each given book that has a tab cover, {@code /covers/<file_key>.jpg}. Books without one are
	 * left out; an empty input gives an empty map. One query.
	 */
	Map<Long, String> of(Collection<Long> bookIds);

}
