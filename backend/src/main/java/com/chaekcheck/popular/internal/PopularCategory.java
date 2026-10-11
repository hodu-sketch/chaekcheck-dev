package com.chaekcheck.popular.internal;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * The ranking lists fetched and shown (plan cycle 10 design #4, ADR-0077): "전체" and 14 first-level categories of
 * the site, in this (request) order. Site ids from the plan research (kpipa.md 2절), checked against the category
 * links ({@code a[data-name="searchCtg"]}, {@code id="ctg_<id>"}) of the saved rank page on 2026-10-12. Left out on
 * purpose: 의학 (1155), 스포츠 (1861), 만화 (2725), 어린이·청소년 (2766), 기타 (9999…9) (ADR-0077), and 언어 (178),
 * 기술·공학 (1991) (user decision 2026-10-12, "'언어' 랑 '기술공학'도 빼주라"). A stored month may still hold lists
 * left out later; they are not shown.
 */
public enum PopularCategory {

	ALL("", "전체"),
	NOVEL("00000000000000000303", "소설"),
	LITERATURE("00000000000000000223", "문학·인물"),
	SOCIETY("00000000000000000449", "사회"),
	PHILOSOPHY("00000000000000001621", "철학·종교"),
	ECONOMY("00000000000000000721", "경제·경영"),
	SELF_HELP("00000000000000002344", "자기계발"),
	ARTS("00000000000000000001", "예술"),
	COMPUTING("00000000000000002169", "컴퓨터·IT"),
	LIVING("00000000000000002476", "생활·취미"),
	SCIENCE("00000000000000001443", "과학"),
	HISTORY("00000000000000001381", "역사"),
	LAW("00000000000000000876", "법"),
	REFERENCE("00000000000000000408", "참고·정보"),
	GEOGRAPHY("00000000000000001782", "지리·환경");

	/** Stored {@code category_code} and API {@code code} of "전체". */
	public static final String ALL_CODE = "ALL";

	private final String siteId;

	private final String shortName;

	PopularCategory(String siteId, String shortName) {
		this.siteId = siteId;
		this.shortName = shortName;
	}

	/** The site's {@code searchCtg} value; empty for "전체". */
	public String siteId() {
		return siteId;
	}

	/** Name shown on the screen and in the API ({@code name}). */
	public String shortName() {
		return shortName;
	}

	/** {@code ALL} or the 20-digit site id: {@code ranking_category.category_code} and the API {@code code}. */
	public String code() {
		return this == ALL ? ALL_CODE : siteId;
	}

	public boolean isAll() {
		return this == ALL;
	}

	/** The 14 categories with a sales count, without "전체", in request order. */
	public static List<PopularCategory> withSales() {
		return Arrays.stream(values()).filter(category -> !category.isAll()).toList();
	}

	public static Optional<PopularCategory> byCode(String code) {
		return Arrays.stream(values()).filter(category -> category.code().equals(code)).findFirst();
	}

}
