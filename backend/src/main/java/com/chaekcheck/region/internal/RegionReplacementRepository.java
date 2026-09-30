package com.chaekcheck.region.internal;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

interface RegionReplacementRepository extends JpaRepository<RegionReplacement, RegionReplacement.Key> {

	List<RegionReplacement> findByRegionIdIn(Collection<Long> regionIds);

}
