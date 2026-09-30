package com.chaekcheck.library.internal;

import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import com.chaekcheck.library.LibraryType;
import com.chaekcheck.library.PlatformType;

/**
 * One e-library site (ADR-0017). Rows come from Liquibase; operators change {@code enabled} with SQL.
 */
@Entity
@Table(schema = "library", name = "library")
class Library {

	@Id
	private Long id;

	@Column(columnDefinition = "text")
	private String name;

	@Enumerated(EnumType.STRING)
	@Column(name = "library_type", columnDefinition = "text")
	private LibraryType libraryType;

	@Enumerated(EnumType.STRING)
	@Column(name = "platform_type", columnDefinition = "text")
	private PlatformType platformType;

	@Column(name = "base_url", columnDefinition = "text")
	private String baseUrl;

	@Column(name = "eligibility_summary", columnDefinition = "text")
	private String eligibilitySummary;

	/** Eligibility we cannot decide from regions, shown as text (e.g. 책이음 members). */
	@Column(name = "extra_condition_text", columnDefinition = "text")
	private String extraConditionText;

	@Column(name = "visit_required")
	private boolean visitRequired;

	@Column(name = "join_method", columnDefinition = "text")
	private String joinMethod;

	/** The library's join or eligibility page; null when not known. */
	@Column(name = "join_url", columnDefinition = "text")
	private String joinUrl;

	/**
	 * Original text that may limit who can really use the e-books, shown as a caution. It does not change the
	 * eligibility (C2-U-11), e.g. anyone may join the website but only residents get a loan card.
	 */
	@Column(name = "join_caution", columnDefinition = "text")
	private String joinCaution;

	/**
	 * Neutral information about who the library serves (e.g. a braille library for the visually impaired), shown as
	 * "안내". Unlike {@link #joinCaution} it changes neither the eligibility, the filter nor the order (2026-09-29).
	 */
	@Column(name = "join_notice", columnDefinition = "text")
	private String joinNotice;

	/**
	 * Front page of what this row is: the e-library's main page for an EBOOK row, the library's own website for a
	 * physical library (later). Null when not verified.
	 */
	@Column(name = "homepage_url", columnDefinition = "text")
	private String homepageUrl;

	/** Ranking value: e-books the site holds, counted on {@link #holdingsCountedOn}. */
	@Column(name = "holdings_count")
	private Integer holdingsCount;

	@Column(name = "holdings_counted_on")
	private LocalDate holdingsCountedOn;

	/** Sites whose list screen gives no availability verdict go last in their priority group. */
	private boolean deferred;

	private boolean enabled;

	protected Library() {
	}

	Long getId() {
		return id;
	}

	String getName() {
		return name;
	}

	LibraryType getLibraryType() {
		return libraryType;
	}

	PlatformType getPlatformType() {
		return platformType;
	}

	String getBaseUrl() {
		return baseUrl;
	}

	String getEligibilitySummary() {
		return eligibilitySummary;
	}

	String getExtraConditionText() {
		return extraConditionText;
	}

	boolean isVisitRequired() {
		return visitRequired;
	}

	String getJoinMethod() {
		return joinMethod;
	}

	String getJoinUrl() {
		return joinUrl;
	}

	String getJoinCaution() {
		return joinCaution;
	}

	String getJoinNotice() {
		return joinNotice;
	}

	String getHomepageUrl() {
		return homepageUrl;
	}

	Integer getHoldingsCount() {
		return holdingsCount;
	}

	boolean isDeferred() {
		return deferred;
	}

	boolean isEnabled() {
		return enabled;
	}

}
