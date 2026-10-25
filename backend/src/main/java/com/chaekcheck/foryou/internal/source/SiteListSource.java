package com.chaekcheck.foryou.internal.source;

import java.util.ArrayList;
import java.util.List;

import com.chaekcheck.availability.RankedListings;
import com.chaekcheck.availability.RankedListings.Failed;
import com.chaekcheck.availability.RankedListings.Page;
import com.chaekcheck.availability.RankedListings.Result;
import com.chaekcheck.availability.RankedListings.Row;
import com.chaekcheck.availability.RankedListings.Sort;

/**
 * The first {@code top} books of each e-library site's loan order (ADR-0148). Sites are read one after another; a site
 * whose page fails is a failed read for the day (no retry), the others go on.
 */
public class SiteListSource implements PopularSource {

	private final String source;

	private final RankedListings rankedListings;

	private final List<Long> libraryIds;

	private final int top;

	public SiteListSource(String source, RankedListings rankedListings, List<Long> libraryIds, int top) {
		this.source = source;
		this.rankedListings = rankedListings;
		this.libraryIds = List.copyOf(libraryIds);
		this.top = top;
	}

	@Override
	public List<SourceRead> read() {
		List<SourceRead> reads = new ArrayList<>(libraryIds.size());
		for (long libraryId : libraryIds) {
			reads.add(readSite(libraryId));
		}
		return reads;
	}

	private SourceRead readSite(long libraryId) {
		List<SourceRow> rows = new ArrayList<>(top);
		for (int page = 1; rows.size() < top; page++) {
			Result result = rankedListings.fetch(libraryId, Sort.LOANS, page);
			if (result instanceof Failed failed) {
				return new SourceRead(source, libraryId, null, List.of(), failed.reason());
			}
			Page got = (Page) result;
			for (Row row : got.rows()) {
				if (rows.size() < top) {
					rows.add(new SourceRow(row.rank(), row.title(), row.author(), row.publisher(), null, row.itemKey(),
							row.loansNow(), row.reservations()));
				}
			}
			if (got.rows().size() < got.pageSize() || (long) page * got.pageSize() >= got.totalCount()) {
				break;
			}
		}
		return new SourceRead(source, libraryId, null, rows, null);
	}

}
