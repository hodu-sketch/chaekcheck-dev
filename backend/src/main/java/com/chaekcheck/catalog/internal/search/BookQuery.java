package com.chaekcheck.catalog.internal.search;

import java.util.List;
import java.util.Set;

import com.chaekcheck.catalog.normalize.CatalogText;
import com.chaekcheck.catalog.normalize.Contributors.Role;

/**
 * A book search (spec 3.2~3.5): filled fields are ANDed; inside a field every word must appear (or, with
 * {@code exact}, the whole normalized value must be equal).
 * @param title field text as sent (trimmed); null when not given
 * @param roles contributor roles searched by the author field (AUTHOR and UNKNOWN always, plus the chosen ones)
 * @param page 1-based
 */
public record BookQuery(String title, String author, String publisher, boolean exactTitle, boolean exactAuthor,
		boolean exactPublisher, Set<Role> roles, Sort sort, int page, int pageSize) {

	public enum Sort {

		DEFAULT, NEWEST, OLDEST

	}

	public List<String> titleWords() {
		return CatalogText.words(title);
	}

	public List<String> authorWords() {
		return CatalogText.words(author);
	}

	public List<String> publisherWords() {
		return CatalogText.words(publisher);
	}

}
