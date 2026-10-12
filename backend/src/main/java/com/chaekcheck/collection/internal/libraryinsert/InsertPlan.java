package com.chaekcheck.collection.internal.libraryinsert;

import java.util.List;
import java.util.Map;

import com.chaekcheck.library.NewLibrary;

/**
 * What the library-insert command works out from a collection DB copy, the rules folder and the service (plan cycle
 * 13 R2-4): the four lists of the report and the libraries left out. Pure values, so the same input gives the same
 * report.
 * @param firstId the id the first new library gets ({@code LibraryRegistry.nextId()})
 * @param goneKnown collection ids that are gone but in {@code gone_known.tsv}: checked by a person, not asked again
 * @param leftOut collection ids left out, by reason (R2-5), not linked libraries only
 */
record InsertPlan(long firstId, List<NewEntry> newLibraries, List<Change> changed, List<Gone> gone,
		List<Long> goneKnown, List<Suspect> suspects, Map<String, List<Long>> leftOut) {

	/** A new library with the collection id it comes from: the link row. */
	record NewEntry(long collectionId, NewLibrary library) {
	}

	/** A linked library whose collected value differs from the service (R2-4). Reported only (R2-9). */
	record Change(long collectionId, long libraryId, String field, String service, String collected) {
	}

	/** A linked library gone from the copy, or not crawled any more (R2-4). Reported only. */
	record Gone(long collectionId, long libraryId, String reason) {
	}

	/**
	 * A new library to be read by a person first (R2-7): {@code SAME_HOST} (same host as another library, with
	 * shared item keys) or {@code ORDER_NO} (order number 2 and up, not in {@code order_no_allowed.tsv}).
	 * @param otherCollectionId the other library of a same-host pair
	 * @param otherLibraryId its service id when it is in the service, else null
	 */
	record Suspect(String kind, long collectionId, Long otherCollectionId, Long otherLibraryId, String detail) {
	}

	boolean hasSameHost() {
		return suspects.stream().anyMatch(s -> s.kind().equals("SAME_HOST"));
	}

	boolean hasOrderNo() {
		return suspects.stream().anyMatch(s -> s.kind().equals("ORDER_NO"));
	}

}
