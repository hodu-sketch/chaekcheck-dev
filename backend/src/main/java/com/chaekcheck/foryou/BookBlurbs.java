package com.chaekcheck.foryou;

import java.util.Collection;
import java.util.Map;

/**
 * The tab blurbs (cycle 23, {@code foryou.book_blurb}: the AI summaries of the books outside the lists), for other
 * modules to read (user 2026-10-25: the AI search's book window of a book outside the lists showed no summary while the
 * 책첵 추천 tab had one for the same book).
 */
public interface BookBlurbs {

	/** The blurb of each given book that has one; books without one are left out. One query. */
	Map<Long, String> of(Collection<Long> bookIds);

}
