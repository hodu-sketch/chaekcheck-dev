package com.chaekcheck.catalog;

/**
 * Public API of the catalog module: the address of a cover picture on CloudFront (cycle 26, ADR-0163),
 * {@code <chaekcheck.covers.base-url>/covers/<file key>.jpg}. The same rule for an edition's cover
 * ({@code catalog.book_cover.file_key}) and a list's own picture ({@code sha1(cover_source)}).
 */
public interface CoverUrls {

	/** The address, or null when the base address is not set or the key is not 40 lower-case hex digits. */
	String of(String fileKey);

}
