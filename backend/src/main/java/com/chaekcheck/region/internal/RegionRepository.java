package com.chaekcheck.region.internal;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

interface RegionRepository extends JpaRepository<Region, Long> {

	List<Region> findByStatusOrderById(Region.Status status);

}
