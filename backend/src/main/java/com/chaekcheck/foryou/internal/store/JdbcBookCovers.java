package com.chaekcheck.foryou.internal.store;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.chaekcheck.foryou.BookCovers;

/** {@link BookCovers} over {@code foryou.book_cover}, read through {@link BookTexts}. */
@Component
class JdbcBookCovers implements BookCovers {

	private final BookTexts texts;

	JdbcBookCovers(BookTexts texts) {
		this.texts = texts;
	}

	@Override
	public Map<Long, String> of(Collection<Long> bookIds) {
		Map<Long, String> found = new HashMap<>();
		texts.coverKeys(bookIds).forEach((bookId, key) -> found.put(bookId, "/covers/" + key + ".jpg"));
		return found;
	}

}
