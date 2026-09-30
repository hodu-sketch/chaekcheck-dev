package com.chaekcheck.availability.internal.adapter;

import java.util.List;

/** @param totalCount total results for the query across all pages, as the site reports it */
public record ParsedPage(int totalCount, List<ParsedItem> items) {

	public ParsedPage {
		items = List.copyOf(items);
	}

}
