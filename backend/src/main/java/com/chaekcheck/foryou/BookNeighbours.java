package com.chaekcheck.foryou;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * The books the crawl PC found closest to a book (KURE-v1 neighbours, ADR-0149), for other modules to read (user
 * 2026-10-25: the AI search adds the neighbours of the books it showed when a follow-up question points at them).
 */
public interface BookNeighbours {

	/**
	 * The neighbour ids of each given book that has neighbours, nearest first (1 to 20). Books without a row are left
	 * out; an empty input gives an empty map. One query.
	 */
	Map<Long, List<Long>> of(Collection<Long> bookIds);

}
