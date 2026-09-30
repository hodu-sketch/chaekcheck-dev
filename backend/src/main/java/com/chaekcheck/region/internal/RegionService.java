package com.chaekcheck.region.internal;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.chaekcheck.region.RegionDirectory;
import com.chaekcheck.region.RegionNotUsableException;
import com.chaekcheck.region.RegionResolution;
import com.chaekcheck.region.RegionResolution.Replacement;
import com.chaekcheck.region.RegionResolution.Status;

@Service
@Transactional(readOnly = true)
class RegionService implements RegionDirectory {

	private final RegionRepository regions;

	private final RegionReplacementRepository replacements;

	RegionService(RegionRepository regions, RegionReplacementRepository replacements) {
		this.regions = regions;
		this.replacements = replacements;
	}

	@Override
	public Map<Long, Set<Long>> expandForMatching(Set<Long> sigunguIds) {
		Map<Long, Region> found = findByIds(sigunguIds);
		Map<Long, RegionNotUsableException.Reason> notUsable = new TreeMap<>();
		for (Long id : sigunguIds) {
			Region region = found.get(id);
			if (region == null) {
				notUsable.put(id, RegionNotUsableException.Reason.NOT_FOUND);
			}
			else if (region.getLevel() != Region.Level.SIGUNGU) {
				notUsable.put(id, RegionNotUsableException.Reason.NOT_SIGUNGU);
			}
			else if (region.getStatus() != Region.Status.ACTIVE) {
				notUsable.put(id, RegionNotUsableException.Reason.ABOLISHED);
			}
		}
		if (!notUsable.isEmpty()) {
			throw new RegionNotUsableException(notUsable);
		}
		Map<Long, Set<Long>> expanded = new LinkedHashMap<>();
		for (Long id : new TreeSet<>(sigunguIds)) {
			expanded.put(id, Set.of(id, found.get(id).getParentId()));
		}
		return expanded;
	}

	@Override
	public List<RegionResolution> resolve(Set<Long> ids) {
		Map<Long, Region> found = findByIds(ids);
		List<RegionResolution> result = new ArrayList<>();
		for (Long id : new TreeSet<>(ids)) {
			Region region = found.get(id);
			if (region == null || region.getLevel() != Region.Level.SIGUNGU) {
				result.add(unknown(id));
			}
			else if (region.getStatus() == Region.Status.ACTIVE) {
				result.add(new RegionResolution(id, Status.ACTIVE, labelOf(region), List.of()));
			}
			else {
				result.add(resolveAbolished(region));
			}
		}
		return result;
	}

	/** Regions for the S2 modal: active SIDOs, each with its active SIGUNGUs, ordered by id. */
	List<SidoNode> activeTree() {
		List<Region> active = regions.findByStatusOrderById(Region.Status.ACTIVE);
		Map<Long, List<SigunguNode>> children = active.stream()
			.filter(r -> r.getLevel() == Region.Level.SIGUNGU)
			.collect(Collectors.groupingBy(Region::getParentId, LinkedHashMap::new,
					Collectors.mapping(r -> new SigunguNode(r.getId(), r.getName()), Collectors.toList())));
		return active.stream()
			.filter(r -> r.getLevel() == Region.Level.SIDO)
			.map(r -> new SidoNode(r.getId(), r.getName(), r.getShortName(), children.getOrDefault(r.getId(), List.of())))
			.toList();
	}

	private RegionResolution resolveAbolished(Region region) {
		Set<Long> finalIds = followReplacements(region.getId());
		if (finalIds.isEmpty()) {
			return unknown(region.getId());
		}
		Map<Long, Region> finals = findByIds(finalIds);
		List<Replacement> replacementList = finalIds.stream()
			.map(finals::get)
			.map(r -> new Replacement(r.getId(), labelOf(r)))
			.toList();
		Status status = replacementList.size() == 1 ? Status.REPLACED : Status.SPLIT;
		return new RegionResolution(region.getId(), status, labelOf(region), replacementList);
	}

	/**
	 * Follows replacement links breadth-first until only active SIGUNGUs remain (A -> B -> C).
	 * Each region is visited once, so a cycle in the links cannot loop forever.
	 */
	private Set<Long> followReplacements(Long startId) {
		Set<Long> visited = new HashSet<>(Set.of(startId));
		Set<Long> frontier = Set.of(startId);
		Set<Long> finalIds = new TreeSet<>();
		while (!frontier.isEmpty()) {
			Set<Long> next = replacements.findByRegionIdIn(frontier)
				.stream()
				.map(RegionReplacement::getReplacedById)
				.filter(visited::add)
				.collect(Collectors.toSet());
			Set<Long> stillAbolished = new HashSet<>();
			for (Region r : regions.findAllById(next)) {
				if (r.isActiveSigungu()) {
					finalIds.add(r.getId());
				}
				else if (r.getStatus() == Region.Status.ABOLISHED) {
					stillAbolished.add(r.getId());
				}
			}
			frontier = stillAbolished;
		}
		return finalIds;
	}

	private String labelOf(Region sigungu) {
		Region parent = regions.findById(sigungu.getParentId()).orElseThrow();
		return parent.displayName() + " " + sigungu.getName();
	}

	private Map<Long, Region> findByIds(Collection<Long> ids) {
		return regions.findAllById(ids).stream().collect(Collectors.toMap(Region::getId, Function.identity()));
	}

	private static RegionResolution unknown(Long id) {
		return new RegionResolution(id, Status.UNKNOWN, null, List.of());
	}

	record SidoNode(long id, String name, String shortName, List<SigunguNode> children) {
	}

	record SigunguNode(long id, String name) {
	}

}
