package com.chaekcheck.picks.internal.lists;

/**
 * The lists' covers are files, not database rows (user 2026-10-20: "이미지 말이야 자바가 아니라 앞에 있어야 하는
 * 거 아니야?"): the collector writes {@code covers/<sha1(url)>.jpg} in the lists folder, and since cycle 26 every list
 * picture, the librarian list's included, is on CloudFront under the same name (ADR-0163); the server stores none
 * (deploy 2, ADR-0166).
 */
public final class CoverFiles {

	private CoverFiles() {
	}

	/** {@code <sha1(url)>.jpg}, the collector's file name for a cover address. */
	public static String fileName(String sourceUrl) {
		return key(sourceUrl) + ".jpg";
	}

	/**
	 * {@code sha1(url)}: the picture's name on CloudFront too ({@code catalog.CoverUrls}, cycle 26 ADR-0163), where every
	 * list picture is, the librarian list's included.
	 */
	public static String key(String sourceUrl) {
		return CuratedLists.sha1(sourceUrl);
	}

}
