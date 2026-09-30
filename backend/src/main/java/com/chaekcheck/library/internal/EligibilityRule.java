package com.chaekcheck.library.internal;

import java.util.Set;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One eligibility rule: basis x scope (ADR-0004). A NATIONWIDE rule means anyone can join; a library with no
 * rules has unverified eligibility (ADR-0013).
 */
@Entity
@Table(schema = "library", name = "eligibility_rule")
class EligibilityRule {

	enum Basis {
		RESIDENCE, WORK, SCHOOL, AFFILIATION
	}

	enum ScopeType {
		REGION, INSTITUTION, NATIONWIDE
	}

	@Id
	private Long id;

	@Column(name = "library_id")
	private Long libraryId;

	@Enumerated(EnumType.STRING)
	@Column(columnDefinition = "text")
	private Basis basis;

	@Enumerated(EnumType.STRING)
	@Column(name = "scope_type", columnDefinition = "text")
	private ScopeType scopeType;

	/** A region module id; no foreign key across modules. */
	@Column(name = "scope_region_id")
	private Long scopeRegionId;

	@Column(name = "scope_institution_id")
	private Long scopeInstitutionId;

	protected EligibilityRule() {
	}

	Long getLibraryId() {
		return libraryId;
	}

	boolean isNationwide() {
		return scopeType == ScopeType.NATIONWIDE;
	}

	/** True when this is a REGION rule of the given basis whose region is in {@code expandedRegionIds}. */
	boolean matchesRegion(Basis wanted, Set<Long> expandedRegionIds) {
		return basis == wanted && scopeType == ScopeType.REGION && expandedRegionIds.contains(scopeRegionId);
	}

}
