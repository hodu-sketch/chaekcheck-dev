package com.chaekcheck.foryou.internal.store;

import java.util.Collection;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.chaekcheck.foryou.BookBlurbs;

/** {@link BookBlurbs} over {@code foryou.book_blurb}, read through {@link BookTexts}. */
@Component
class JdbcBookBlurbs implements BookBlurbs {

	private final BookTexts texts;

	JdbcBookBlurbs(BookTexts texts) {
		this.texts = texts;
	}

	@Override
	public Map<Long, String> of(Collection<Long> bookIds) {
		return texts.blurbs(bookIds);
	}

}
