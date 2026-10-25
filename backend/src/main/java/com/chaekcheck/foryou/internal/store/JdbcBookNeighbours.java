package com.chaekcheck.foryou.internal.store;

import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.chaekcheck.foryou.BookNeighbours;

/** {@link BookNeighbours} over {@code foryou.book_neighbour}, read through {@link BookTexts}. */
@Component
class JdbcBookNeighbours implements BookNeighbours {

	private final BookTexts texts;

	JdbcBookNeighbours(BookTexts texts) {
		this.texts = texts;
	}

	@Override
	public Map<Long, List<Long>> of(Collection<Long> bookIds) {
		Map<Long, List<Long>> found = new HashMap<>();
		texts.neighbours(bookIds)
			.forEach((bookId, ids) -> found.put(bookId, Arrays.stream(ids).mapToObj(id -> (long) id).toList()));
		return found;
	}

}
