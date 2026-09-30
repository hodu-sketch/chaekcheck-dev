package com.chaekcheck.availability.internal;

/**
 * Which book field the search text is matched against. KYOBO and YES24 match partially ("채식" finds "채식의 철학");
 * the portal's author and publisher filters match exactly (ADR-0026). The APIs take it as the name of the one
 * condition parameter: {@code title}, {@code author} or {@code publisher} (ADR-0025).
 * <p>
 * Translators have no field of their own: YES24 lists them in the author text ("에리히 프롬 저/김석희 역"),
 * so AUTHOR finds them there; KYOBO lists only the main author, so translators cannot be searched there.
 */
public enum SearchField {
	TITLE, AUTHOR, PUBLISHER
}
