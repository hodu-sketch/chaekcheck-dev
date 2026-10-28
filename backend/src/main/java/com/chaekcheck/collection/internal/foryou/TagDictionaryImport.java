package com.chaekcheck.collection.internal.foryou;

import java.nio.file.Path;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.chaekcheck.catalog.BookTextsTransfer;
import com.chaekcheck.catalog.BookTextsTransfer.DictionaryRow;

/**
 * The {@code tag-dictionary} import mode (cycle 23 step 3-3, ADR-0149 decision #9): the confirmed dictionary into
 * {@code catalog.tag_dictionary} (since cycle 26) through {@link BookTextsTransfer#replaceTagDictionary} in one transaction. Codes not in the
 * file are made inactive, never deleted, so tags already given keep their meaning; how many is reported. Put it in
 * before a {@code book-texts} file with {@code tags} rows, whose unknown codes are skipped.
 */
public final class TagDictionaryImport {

	private static final Logger log = LoggerFactory.getLogger(TagDictionaryImport.class);

	private final BookTextsTransfer bookTags;

	public TagDictionaryImport(BookTextsTransfer bookTags) {
		this.bookTags = bookTags;
	}

	/** @param madeInactive codes active before and not in the file */
	public record Report(int codes, int madeInactive) {
	}

	/** @throws TagDictionaryFile.RefusedException when the file is refused; nothing is written */
	public Report run(Path file) {
		List<DictionaryRow> rows = TagDictionaryFile.read(file);
		int madeInactive = bookTags.replaceTagDictionary(rows);
		Report report = new Report(rows.size(), madeInactive);
		log.info("tag-dictionary: codes {}, made inactive {}", report.codes(), report.madeInactive());
		if (madeInactive > 0) {
			log.warn("tag-dictionary: {} code(s) not in the file are now inactive; their tags stay but are not shown",
					madeInactive);
		}
		return report;
	}

}
