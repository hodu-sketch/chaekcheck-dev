package com.chaekcheck.region.internal;

import java.io.Serializable;
import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

/**
 * Link from an abolished region to a region that replaced it. Two or more links from one region mean a split.
 */
@Entity
@Table(schema = "region", name = "region_replacement")
@IdClass(RegionReplacement.Key.class)
class RegionReplacement {

	record Key(Long regionId, Long replacedById) implements Serializable {
	}

	@Id
	@Column(name = "region_id")
	private Long regionId;

	@Id
	@Column(name = "replaced_by_id")
	private Long replacedById;

	@Column(name = "effective_on")
	private LocalDate effectiveOn;

	protected RegionReplacement() {
	}

	Long getRegionId() {
		return regionId;
	}

	Long getReplacedById() {
		return replacedById;
	}

	LocalDate getEffectiveOn() {
		return effectiveOn;
	}

}
