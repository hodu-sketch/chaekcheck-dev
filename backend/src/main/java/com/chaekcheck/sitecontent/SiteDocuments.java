package com.chaekcheck.sitecontent;

/**
 * The current version of the documents a member agrees to at sign-up (ADR-0099): the highest version number whose
 * effective day is on or before today (Asia/Seoul). Read from the database on every call.
 */
public interface SiteDocuments {

	/**
	 * @throws IllegalStateException when the document has no version in effect (never with the seed in place)
	 */
	int currentVersion(ConsentDocument document);

}
