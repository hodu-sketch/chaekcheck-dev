package com.chaekcheck.library.internal;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.chaekcheck.library.Eligibility;
import com.chaekcheck.library.InvalidRegionException;
import com.chaekcheck.library.InvalidRegionException.InvalidRegion;
import com.chaekcheck.library.LibraryCatalog;
import com.chaekcheck.library.LibraryRef;
import com.chaekcheck.library.PlanOptions;
import com.chaekcheck.library.PriorityGroup;
import com.chaekcheck.library.SearchTarget;
import com.chaekcheck.region.RegionDirectory;
import com.chaekcheck.region.RegionNotUsableException;

@Service
@Transactional(readOnly = true)
class LibraryService implements LibraryCatalog {

	static final String RESIDENCE_FIELD = "residenceRegionIds";

	static final String WORK_FIELD = "workRegionIds";

	private static final List<PriorityGroup> ORDER_WITH_REGIONS = List.of(PriorityGroup.MY_SIGUNGU,
			PriorityGroup.MY_SIDO, PriorityGroup.OPEN, PriorityGroup.NEEDS_CHECK);

	private static final List<PriorityGroup> ORDER_WITHOUT_REGIONS = List.of(PriorityGroup.OPEN,
			PriorityGroup.QUALIFIED, PriorityGroup.NEEDS_CHECK);

	/** Groups whose eligibility is OPEN or ELIGIBLE; their visit-only libraries go after all of them (ADR-0027). */
	private static final Set<PriorityGroup> JOINABLE = Set.of(PriorityGroup.MY_SIGUNGU, PriorityGroup.MY_SIDO,
			PriorityGroup.OPEN);

	/**
	 * Inside a group: visit-only libraries last (only matters in groups that are not joinable, see ADR-0027), then
	 * deferred last [확정], then libraries without a join caution first (user request 2026-09-29, C2-U-11), then
	 * holdings count descending with unknown last, then id [안].
	 */
	private static final Comparator<Library> IN_GROUP = Comparator.comparing(Library::isVisitRequired)
		.thenComparing(Library::isDeferred)
		.thenComparing(library -> library.getJoinCaution() != null)
		.thenComparing(Library::getHoldingsCount, Comparator.nullsLast(Comparator.reverseOrder()))
		.thenComparing(Library::getId);

	private final LibraryRepository libraries;

	private final EligibilityRuleRepository rules;

	private final RegionDirectory regionDirectory;

	LibraryService(LibraryRepository libraries, EligibilityRuleRepository rules, RegionDirectory regionDirectory) {
		this.libraries = libraries;
		this.rules = rules;
		this.regionDirectory = regionDirectory;
	}

	@Override
	public List<SearchTarget> planSearch(Set<Long> residenceIds, Set<Long> workIds, PlanOptions options) {
		List<InvalidRegion> invalid = new ArrayList<>();
		UserRegions residence = expand(RESIDENCE_FIELD, residenceIds, invalid);
		UserRegions work = expand(WORK_FIELD, workIds, invalid);
		if (!invalid.isEmpty()) {
			throw new InvalidRegionException(invalid);
		}
		boolean regionFilterApplied = !residenceIds.isEmpty() || !workIds.isEmpty();

		// enabled is read on every request so an operator's SQL change applies at once (ADR-0004)
		List<Library> enabled = libraries.findByEnabledTrueOrderById();
		Map<Long, List<EligibilityRule>> rulesByLibrary = rulesOf(enabled);

		Map<PriorityGroup, List<Library>> groups = new EnumMap<>(PriorityGroup.class);
		Map<Long, Placement> placements = new HashMap<>();
		for (Library library : enabled) {
			Placement placement = place(library, rulesByLibrary.getOrDefault(library.getId(), List.of()),
					regionFilterApplied, residence, work);
			if (placement != null) {
				placements.put(library.getId(), placement);
				groups.computeIfAbsent(placement.group(), g -> new ArrayList<>()).add(library);
			}
		}

		// ADR-0027: over all joinable groups, libraries without a visit come first (in group order), then the visit
		// ones in the same group order. Groups that are not joinable keep the visit ones last inside the group.
		List<PriorityGroup> order = regionFilterApplied ? ORDER_WITH_REGIONS : ORDER_WITHOUT_REGIONS;
		List<SearchTarget> plan = new ArrayList<>();
		for (boolean visit : List.of(false, true)) {
			for (PriorityGroup group : order) {
				if (JOINABLE.contains(group)) {
					groups.getOrDefault(group, List.of())
						.stream()
						.filter(library -> library.isVisitRequired() == visit)
						.sorted(IN_GROUP)
						.forEach(library -> plan.add(toTarget(library, placements.get(library.getId()))));
				}
			}
		}
		for (PriorityGroup group : order) {
			if (!JOINABLE.contains(group)) {
				groups.getOrDefault(group, List.of())
					.stream()
					.sorted(IN_GROUP)
					.forEach(library -> plan.add(toTarget(library, placements.get(library.getId()))));
			}
		}
		return options.apply(plan);
	}

	@Override
	public List<LibraryRef> enabledLibraryRefs() {
		return libraries.findByEnabledTrueOrderById()
			.stream()
			.map(library -> new LibraryRef(library.getId(), library.getBaseUrl(), library.getPlatformType()))
			.toList();
	}

	@Override
	public Optional<SearchTarget> findEnabledTarget(long libraryId) {
		return libraries.findById(libraryId).filter(Library::isEnabled).map(library -> {
			List<EligibilityRule> libraryRules = rulesOf(List.of(library)).getOrDefault(libraryId, List.of());
			return toTarget(library, place(library, libraryRules, false, UserRegions.NONE, UserRegions.NONE));
		});
	}

	/**
	 * Group and eligibility of one library (design-delta table), or null when it is left out: with regions, a
	 * library that only has region rules and none of them match. SCHOOL and AFFILIATION rules are never compared.
	 * When several rules match, the earliest group wins (SIGUNGU, then SIDO, then anyone) [안].
	 */
	private static Placement place(Library library, List<EligibilityRule> libraryRules, boolean regionFilterApplied,
			UserRegions residence, UserRegions work) {
		boolean nationwide = libraryRules.stream().anyMatch(EligibilityRule::isNationwide);
		boolean otherRules = libraryRules.stream().anyMatch(rule -> !rule.isNationwide());
		boolean extraCondition = library.getExtraConditionText() != null;
		if (!regionFilterApplied) {
			if (nationwide) {
				return new Placement(PriorityGroup.OPEN, Eligibility.OPEN);
			}
			return otherRules ? new Placement(PriorityGroup.QUALIFIED, Eligibility.UNKNOWN)
					: new Placement(PriorityGroup.NEEDS_CHECK, Eligibility.UNVERIFIED);
		}
		if (matches(libraryRules, residence.sigungu(), work.sigungu())) {
			return new Placement(PriorityGroup.MY_SIGUNGU, Eligibility.ELIGIBLE);
		}
		if (matches(libraryRules, residence.sido(), work.sido())) {
			return new Placement(PriorityGroup.MY_SIDO, Eligibility.ELIGIBLE);
		}
		if (nationwide) {
			return new Placement(PriorityGroup.OPEN, Eligibility.OPEN);
		}
		if (libraryRules.isEmpty() || extraCondition) {
			return new Placement(PriorityGroup.NEEDS_CHECK, Eligibility.UNVERIFIED);
		}
		return null;
	}

	private static boolean matches(List<EligibilityRule> libraryRules, Set<Long> residenceScope,
			Set<Long> workScope) {
		return libraryRules.stream()
			.anyMatch(rule -> rule.matchesRegion(EligibilityRule.Basis.RESIDENCE, residenceScope)
					|| rule.matchesRegion(EligibilityRule.Basis.WORK, workScope));
	}

	private Map<Long, List<EligibilityRule>> rulesOf(List<Library> libraryList) {
		return rules.findByLibraryIdIn(libraryList.stream().map(Library::getId).toList())
			.stream()
			.collect(Collectors.groupingBy(EligibilityRule::getLibraryId));
	}

	private static SearchTarget toTarget(Library library, Placement placement) {
		return new SearchTarget(library.getId(), library.getName(), library.getLibraryType(),
				library.getPlatformType(), library.getBaseUrl(), placement.group(), library.isDeferred(),
				placement.eligibility(), library.getEligibilitySummary(), library.getExtraConditionText(),
				library.isVisitRequired(), library.getJoinMethod(), library.getJoinUrl(), library.getJoinCaution(),
				library.getJoinNotice(), library.getHomepageUrl());
	}

	/**
	 * S = the chosen SIGUNGU ids, D = their SIDO ids (expandForMatching minus S). Bad ids are collected into
	 * {@code invalid}.
	 */
	private UserRegions expand(String field, Set<Long> sigunguIds, Collection<InvalidRegion> invalid) {
		if (sigunguIds.isEmpty()) {
			return UserRegions.NONE;
		}
		try {
			Set<Long> sido = new HashSet<>();
			regionDirectory.expandForMatching(sigunguIds).values().forEach(sido::addAll);
			sido.removeAll(sigunguIds);
			return new UserRegions(Set.copyOf(sigunguIds), Set.copyOf(sido));
		}
		catch (RegionNotUsableException ex) {
			ex.reasons()
				.entrySet()
				.stream()
				.sorted(Map.Entry.comparingByKey())
				.forEach(e -> invalid.add(new InvalidRegion(field, e.getKey(), reason(e.getValue()))));
			return UserRegions.NONE;
		}
	}

	private static InvalidRegionException.Reason reason(RegionNotUsableException.Reason reason) {
		return switch (reason) {
			case ABOLISHED -> InvalidRegionException.Reason.ABOLISHED;
			case NOT_FOUND -> InvalidRegionException.Reason.NOT_FOUND;
			case NOT_SIGUNGU -> InvalidRegionException.Reason.NOT_SIGUNGU;
		};
	}

	private record UserRegions(Set<Long> sigungu, Set<Long> sido) {

		static final UserRegions NONE = new UserRegions(Set.of(), Set.of());

	}

	private record Placement(PriorityGroup group, Eligibility eligibility) {
	}

}
