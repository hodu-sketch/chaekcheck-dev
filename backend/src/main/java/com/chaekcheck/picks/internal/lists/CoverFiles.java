package com.chaekcheck.picks.internal.lists;

/**
 * The curated lists' covers are files, not database rows (user 2026-10-20: "이미지 말이야 자바가 아니라 앞에 있어야 하는
 * 거 아니야?"): the collector writes {@code covers/<sha1(url)>.jpg} in the lists folder, the web server serves that
 * folder at {@link #URL_PATH} (deploy/nginx, docs/maintenance/3-import.md 14절), and the API names the file. The
 * librarian list's covers, fetched by the server itself, stay in {@code picks.cover}.
 */
public final class CoverFiles {

	/** Where the web server serves the covers folder [확정] (user 2026-10-20, 안 A). */
	public static final String URL_PATH = "/covers/";

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
