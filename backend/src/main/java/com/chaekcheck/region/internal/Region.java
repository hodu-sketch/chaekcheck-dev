package com.chaekcheck.region.internal;

import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A SIDO or SIGUNGU. Rows come only from Liquibase changesets; the application never writes them.
 */
@Entity
@Table(schema = "region", name = "region")
class Region {

	enum Level {
		SIDO, SIGUNGU
	}

	enum Status {
		ACTIVE, ABOLISHED
	}

	@Id
	private Long id;

	@Column(name = "parent_id")
	private Long parentId;

	@Enumerated(EnumType.STRING)
	@Column(columnDefinition = "text")
	private Level level;

	@Column(columnDefinition = "text")
	private String name;

	@Column(name = "short_name", columnDefinition = "text")
	private String shortName;

	@Column(name = "legal_dong_code", columnDefinition = "text")
	private String legalDongCode;

	@Enumerated(EnumType.STRING)
	@Column(columnDefinition = "text")
	private Status status;

	@Column(name = "abolished_on")
	private LocalDate abolishedOn;

	protected Region() {
	}

	Long getId() {
		return id;
	}

	Long getParentId() {
		return parentId;
	}

	Level getLevel() {
		return level;
	}

	String getName() {
		return name;
	}

	String getShortName() {
		return shortName;
	}

	String getLegalDongCode() {
		return legalDongCode;
	}

	Status getStatus() {
		return status;
	}

	LocalDate getAbolishedOn() {
		return abolishedOn;
	}

	boolean isActiveSigungu() {
		return level == Level.SIGUNGU && status == Status.ACTIVE;
	}

	/** Short display name for a SIDO, e.g. "서울"; the full name when no short name is set. */
	String displayName() {
		return shortName != null ? shortName : name;
	}

}
