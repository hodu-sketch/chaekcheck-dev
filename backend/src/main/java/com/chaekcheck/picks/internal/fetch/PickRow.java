package com.chaekcheck.picks.internal.fetch;

import java.time.LocalDate;

/**
 * One pick as stored in {@code picks.pick} (plan cycle 14 design-delta "데이터"). Two lists are the same when they hold
 * the same rows with every value equal ({@link #equals}).
 * @param recomNo the API's {@code recomNo}, 1 to 20 digits
 * @param drCode the API's field code (any 1 to 20 characters, ADR-0089)
 * @param drCodeName the API's field name, null when empty (the theme field has none)
 * @param recomMonth the first day of the recommendation month
 * @param registeredOn the API's {@code regdate}, null when it cannot be read
 * @param isbn13 null when the API's value is not an ISBN
 * @param coverSource the cover address made https, null when it is not on {@code www.nl.go.kr/afile/}
 */
public record PickRow(String recomNo, String drCode, String drCodeName, LocalDate recomMonth, LocalDate registeredOn,
		String title, String author, String publisher, String isbn13, String coverSource) {
}
