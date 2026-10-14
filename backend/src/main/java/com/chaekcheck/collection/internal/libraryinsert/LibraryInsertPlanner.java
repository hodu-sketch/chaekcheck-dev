package com.chaekcheck.collection.internal.libraryinsert;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.springframework.web.util.HtmlUtils;

import com.chaekcheck.collection.internal.SiteHosts;
import com.chaekcheck.collection.internal.importer.HoldingRowFilter;
import com.chaekcheck.collection.internal.libraryinsert.CollectedLibraries.CollectedLibrary;
import com.chaekcheck.collection.internal.libraryinsert.InsertPlan.Change;
import com.chaekcheck.collection.internal.libraryinsert.InsertPlan.Gone;
import com.chaekcheck.collection.internal.libraryinsert.InsertPlan.NewEntry;
import com.chaekcheck.collection.internal.libraryinsert.InsertPlan.Suspect;
import com.chaekcheck.collection.internal.libraryinsert.RuleSet.PublicJoin;
import com.chaekcheck.collection.internal.libraryinsert.RuleSet.Rule;
import com.chaekcheck.library.JoinSurvey;
import com.chaekcheck.library.LendingModel;
import com.chaekcheck.library.NewEligibilityRule;
import com.chaekcheck.library.NewLibrary;
import com.chaekcheck.library.PlatformType;
import com.chaekcheck.library.RegisteredLibrary;
import com.chaekcheck.library.Vendor;

/**
 * Works out the {@link InsertPlan} (plan cycle 13 R2-4 to R2-7), ported from the old seed script
 * {@code scripts/library/build_library_seed.py} so both give the same libraries on the same copy (AC-6). No database,
 * no files: the caller reads the copy, the rules folder and the service.
 *
 * <ul>
 * <li>Left out, the first rule that matches (R2-5): KYOBO rental or mixed (probe), {@code crawl_skip}, no full
 * success, demo or closed, same site, kind TEST; then, last, no e-books (cycle 18, ADR-0110): no holding row the
 * import would take ({@link HoldingRowFilter}, counted against the host of the address the library would get). A
 * library left with no kind is a rules error; the no e-books check comes first, so a library without e-books never
 * stops the run on a missing rule.</li>
 * <li>Values (R2-6): ids from {@code nextId} in collection id order; name = collected name, then the name override
 * of its service id, then HTML characters unescaped (pre-decision 8, {@link HtmlUtils#htmlUnescape}); vendor = the
 * collected vendor, else by platform; lending model by platform (probe, BOOKCUBE volumes, portal null, else
 * owned); order number over every service library and the new ones; base and homepage URL = the final origin
 * (a new library needs a {@code final_origins.tsv} row, user choice 2026-10-12).</li>
 * <li>Same host (R2-7): every new library against every service library and the new ones before it; a pair with
 * shared item keys is a suspect unless the new library is in {@code same_host_kept.tsv}. Hosts are compared without
 * scheme and port ({@link SiteHosts}: {@code java.net.URI} gives no host for names with {@code _}).</li>
 * <li>Changed and gone (R2-4): linked libraries whose collected address (final origin applied), name (override
 * applied), platform or lending model differ, or that are missing, not crawled or never fully crawled. Disabled
 * service libraries are not compared; gone libraries in {@code gone_known.tsv} are only counted.</li>
 * </ul>
 */
class LibraryInsertPlanner {

	/**
	 * Left out because no holding row would be imported (cycle 18, ADR-0110): only audiobooks or videos, no rows, or
	 * detail URLs on another host than the address the library would get. Shown as "전자책 0권".
	 */
	static final String NO_EBOOKS = "no_ebooks";

	/** Collection ids from here on were found after the first 278 libraries (the seed rule, ADR-0052). */
	static final long FIRST_COLLECTED_ONLY_ID = 100_001;

	/** The join method of an unknown join text in the survey; stored as null for NOT_FOUND (old seed script). */
	static final String JOIN_METHOD_UNKNOWN = "도서관 홈페이지의 회원가입 안내 확인";

	static final Map<String, Vendor> VENDORS = Map.of("KYOBO_V1", Vendor.KYOBO, "KYOBO_SUBSCRIPTION_V1",
			Vendor.KYOBO, "YES24_V1", Vendor.YES24, "YES24_V2", Vendor.YES24, "YES24_V3", Vendor.YES24, "BOOKCUBE_FX",
			Vendor.BOOKCUBE, "ALADIN_V1", Vendor.ALADIN, "ELIB_PORTAL_V1", Vendor.PORTAL, "GANGNAM_V1",
			Vendor.IN_HOUSE);

	/** Item keys of the copy, for the same-host check. */
	interface Holdings {

		/** Item keys of a library not marked removed. */
		long keys(long collectionId);

		/** Item keys both libraries have. */
		long shared(long collectionId, long otherCollectionId);

		/**
		 * Holding rows of a library the import would take ({@link HoldingRowFilter} passes them), detail URLs
		 * checked against {@code libraryHost} (cycle 18).
		 */
		long importableRows(long collectionId, String libraryHost);

	}

	private final RuleSet rules;

	LibraryInsertPlanner(RuleSet rules) {
		this.rules = rules;
	}

	/**
	 * @param links collection id to service id ({@code collection.collection_link})
	 * @param nextId {@code LibraryRegistry.nextId()}
	 * @throws LibraryInsertException a rules error (exit 1)
	 */
	InsertPlan plan(List<CollectedLibrary> collected, List<RegisteredLibrary> service, Map<Long, Long> links,
			long nextId, Holdings holdings) {
		Map<Long, CollectedLibrary> byCollectionId = new LinkedHashMap<>();
		for (CollectedLibrary library : collected) {
			if (library.vendor() != null && !Set.of("KYOBO", "YES24", "BOOKCUBE", "ALADIN", "PORTAL", "IN_HOUSE")
				.contains(library.vendor())) {
				throw LibraryInsertException.rules("unknown vendor in the collection DB: " + library.id() + " "
						+ library.vendor());
			}
			byCollectionId.put(library.id(), library);
		}
		Map<Long, RegisteredLibrary> byServiceId = new HashMap<>();
		service.forEach(library -> byServiceId.put(library.id(), library));
		Map<Long, Long> collectionIdOf = new HashMap<>();
		links.forEach((collectionId, serviceId) -> collectionIdOf.put(serviceId, collectionId));

		Map<String, List<Long>> leftOut = new LinkedHashMap<>();
		for (String reason : List.of("kyobo_rental_mixed", "crawl_skip", "no_full_success", "demo_or_closed",
				"same_site", "test", NO_EBOOKS)) {
			leftOut.put(reason, new ArrayList<>());
		}
		List<CollectedLibrary> chosen = new ArrayList<>();
		for (CollectedLibrary library : byCollectionId.values()) {
			if (links.containsKey(library.id())) {
				continue;
			}
			String reason = leftOutReason(library);
			if (reason == null && holdings.importableRows(library.id(), SiteHosts.host(insertedBaseUrl(library))) == 0) {
				// last reason, before the rules checks below (cycle 18, ADR-0110): never asked, the exit code stays
				reason = NO_EBOOKS;
			}
			if (reason != null) {
				leftOut.get(reason).add(library.id());
				continue;
			}
			if (!rules.kinds().containsKey(library.id())) {
				throw LibraryInsertException.rules("no kind for library " + library.id() + " " + library.name()
						+ " (library_kinds.tsv)");
			}
			if (!VENDORS.containsKey(library.platformType())) {
				throw LibraryInsertException.rules("unknown platform " + library.platformType() + " of library "
						+ library.id());
			}
			if (!rules.finalOrigins().containsKey(library.baseUrl())) {
				throw LibraryInsertException.rules("no final_origins.tsv row for new library " + library.id() + " "
						+ library.baseUrl());
			}
			chosen.add(library);
		}

		// order numbers count every service library, disabled ones too (ADR-0071)
		Map<List<String>, Integer> groups = new HashMap<>();
		for (RegisteredLibrary library : service) {
			groups.merge(List.of(library.name(), String.valueOf(library.vendor()),
					String.valueOf(library.lendingModel())), 1, Integer::sum);
		}
		List<NewEntry> added = new ArrayList<>();
		List<Suspect> suspects = new ArrayList<>();
		long id = nextId;
		for (CollectedLibrary library : chosen) {
			NewLibrary built = build(library, id++, groups);
			added.add(new NewEntry(library.id(), built));
			if (built.orderNo() > 1 && !Objects.equals(rules.orderNoAllowed().get(library.id()), built.orderNo())) {
				suspects.add(new Suspect("ORDER_NO", library.id(), null, null, "order_no " + built.orderNo() + " of ("
						+ built.name() + ", " + built.vendor() + ", " + built.lendingModel() + ")"));
			}
		}
		suspects.addAll(sameHost(added, byCollectionId, service, collectionIdOf, holdings));

		List<Change> changed = new ArrayList<>();
		List<Gone> gone = new ArrayList<>();
		List<Long> goneKnown = new ArrayList<>();
		links.entrySet()
			.stream()
			.sorted(Map.Entry.comparingByKey())
			.forEach(link -> compare(link.getKey(), link.getValue(), byCollectionId.get(link.getKey()),
					byServiceId.get(link.getValue()), changed, gone));
		gone.removeIf(entry -> {
			boolean known = rules.goneKnown().contains(entry.collectionId());
			if (known) {
				goneKnown.add(entry.collectionId());
			}
			return known;
		});

		Map<String, List<Long>> leftOutCopy = new LinkedHashMap<>();
		leftOut.forEach((reason, ids) -> leftOutCopy.put(reason, List.copyOf(ids)));
		return new InsertPlan(nextId, List.copyOf(added), List.copyOf(changed), List.copyOf(gone),
				List.copyOf(goneKnown), List.copyOf(suspects), leftOutCopy);
	}

	/** The address a new library would get: its final origin, else the collected one. */
	private String insertedBaseUrl(CollectedLibrary library) {
		return rules.finalOrigins().getOrDefault(library.baseUrl(), library.baseUrl());
	}

	private String leftOutReason(CollectedLibrary library) {
		if (kyoboRentalOrMixed(library)) {
			return "kyobo_rental_mixed";
		}
		if (library.crawlSkip() != null) {
			return "crawl_skip";
		}
		if (library.lastFullSuccessAt() == null) {
			return "no_full_success";
		}
		if (rules.demoOrClosed().containsKey(library.id())) {
			return "demo_or_closed";
		}
		if (rules.sameSite().containsKey(library.id())) {
			return "same_site";
		}
		if ("TEST".equals(rules.kinds().get(library.id()))) {
			return "test";
		}
		return null;
	}

	private boolean kyoboRentalOrMixed(CollectedLibrary library) {
		if (!"KYOBO_SUBSCRIPTION_V1".equals(library.platformType())) {
			return false;
		}
		String model = rules.kyoboProbe().get(RuleSet.stripSlash(library.baseUrl()));
		return "RENTAL".equals(model) || "MIXED".equals(model);
	}

	private NewLibrary build(CollectedLibrary library, long id, Map<List<String>, Integer> groups) {
		String name = HtmlUtils.htmlUnescape(overridden(id, library.name()));
		Vendor vendor = vendorOf(library);
		LendingModel lending = lendingModel(library);
		int orderNo = groups.merge(List.of(name, String.valueOf(vendor), String.valueOf(lending)), 1, Integer::sum);
		String baseUrl = rules.finalOrigins().get(library.baseUrl());
		String kind = rules.kinds().get(library.id());
		PublicJoin join = rules.publicJoins().get(library.id());
		PlatformType platform = PlatformType.valueOf(library.platformType());
		if (join == null) {
			JoinSurvey survey;
			if ("UNKNOWN".equals(kind)) {
				survey = JoinSurvey.NOT_SURVEYED;
			}
			else if (RuleSet.MEMBERS_ONLY_KINDS.contains(kind)) {
				survey = JoinSurvey.MEMBERS_ONLY;
			}
			else {
				throw LibraryInsertException.rules("library " + library.id() + " of kind " + kind
						+ " has no public_join.tsv row");
			}
			return new NewLibrary(id, name, platform, vendor, lending, orderNo, baseUrl, baseUrl, null, null, false,
					null, null, null, null, null, null, false, survey, List.of());
		}
		return switch (join.eligibility()) {
			case "UNVERIFIED" -> new NewLibrary(id, name, platform, vendor, lending, orderNo, baseUrl, baseUrl, null,
					null, false, JOIN_METHOD_UNKNOWN.equals(join.joinMethod()) ? null : join.joinMethod(), null, null,
					null, null, null, false, JoinSurvey.NOT_FOUND, List.of());
			// user decision 2026-10-05: members of an institution only
			case "AFFILIATION" -> new NewLibrary(id, name, platform, vendor, lending, orderNo, baseUrl, baseUrl, null,
					null, join.visitRequired(), join.joinMethod(), join.sourceUrl(), join.joinCaution(), null, null,
					null, false, JoinSurvey.MEMBERS_ONLY, List.of());
			default -> new NewLibrary(id, name, platform, vendor, lending, orderNo, baseUrl, baseUrl,
					"REGION".equals(join.eligibility()) ? join.eligibilitySummary() : null, join.extraCondition(),
					join.visitRequired(), join.joinMethod() == null ? JOIN_METHOD_UNKNOWN : join.joinMethod(),
					join.sourceUrl(), join.joinCaution(), null, null, null, false, JoinSurvey.SURVEYED,
					join.rules().stream().map(LibraryInsertPlanner::rule).toList());
		};
	}

	private static NewEligibilityRule rule(Rule rule) {
		return rule.regionId() == null ? new NewEligibilityRule(rule.basis(), "NATIONWIDE", null)
				: new NewEligibilityRule(rule.basis(), "REGION", rule.regionId());
	}

	/** The name override of a service id, when the list's old name (or already the new one) is the name. */
	private String overridden(long serviceId, String name) {
		RuleSet.NameOverride override = rules.nameOverrides().get(serviceId);
		if (override == null) {
			return name;
		}
		if (!name.equals(override.oldName()) && !name.equals(override.newName())) {
			throw LibraryInsertException.rules("name override for " + serviceId + ": the name is " + name
					+ ", the list says " + override.oldName());
		}
		return override.newName();
	}

	private Vendor vendorOf(CollectedLibrary library) {
		return library.vendor() != null ? Vendor.valueOf(library.vendor()) : VENDORS.get(library.platformType());
	}

	/** Plan cycle 09 spec R3, as the old script's {@code lending_model_of}. */
	LendingModel lendingModel(CollectedLibrary library) {
		return switch (library.platformType()) {
			case "ELIB_PORTAL_V1" -> null;
			case "KYOBO_SUBSCRIPTION_V1" -> {
				String model = rules.kyoboProbe().get(RuleSet.stripSlash(library.baseUrl()));
				yield model == null ? LendingModel.SUBSCRIPTION : LendingModel.valueOf(model);
			}
			case "BOOKCUBE_FX" -> {
				RuleSet.Volume volume = rules.bookcubeVolumes().get(library.id());
				yield volume != null && volume.totalVolume() != null && volume.totalVolume() == 0
						&& volume.totalItem() > 0 ? LendingModel.SUBSCRIPTION : LendingModel.OWNED;
			}
			default -> LendingModel.OWNED;
		};
	}

	private List<Suspect> sameHost(List<NewEntry> added, Map<Long, CollectedLibrary> collected,
			List<RegisteredLibrary> service, Map<Long, Long> collectionIdOf, Holdings holdings) {
		List<Suspect> suspects = new ArrayList<>();
		for (int i = 0; i < added.size(); i++) {
			NewEntry entry = added.get(i);
			if (rules.sameHostKept().contains(entry.collectionId())) {
				continue;
			}
			String host = host(collected.get(entry.collectionId()).baseUrl());
			// the service libraries, then the new ones before this one
			for (RegisteredLibrary other : service) {
				Long otherCollectionId = collectionIdOf.get(other.id());
				if (otherCollectionId == null) {
					continue;
				}
				CollectedLibrary otherCollected = collected.get(otherCollectionId);
				String otherHost = host(otherCollected != null ? otherCollected.baseUrl() : other.baseUrl());
				if (host.equals(otherHost)) {
					check(entry.collectionId(), otherCollectionId, other.id(), holdings, suspects);
				}
			}
			for (NewEntry other : added.subList(0, i)) {
				if (host.equals(host(collected.get(other.collectionId()).baseUrl()))) {
					check(entry.collectionId(), other.collectionId(), null, holdings, suspects);
				}
			}
		}
		return suspects;
	}

	private static void check(long collectionId, long otherCollectionId, Long otherLibraryId, Holdings holdings,
			List<Suspect> suspects) {
		long shared = holdings.shared(collectionId, otherCollectionId);
		if (shared > 0) {
			suspects.add(new Suspect("SAME_HOST", collectionId, otherCollectionId, otherLibraryId,
					"keys " + holdings.keys(collectionId) + " / " + holdings.keys(otherCollectionId) + ", shared "
							+ shared));
		}
	}

	private void compare(long collectionId, long serviceId, CollectedLibrary collected, RegisteredLibrary service,
			List<Change> changed, List<Gone> gone) {
		if (service == null || !service.enabled()) {
			return;
		}
		if (collected == null) {
			gone.add(new Gone(collectionId, serviceId, "not in the collection DB"));
			return;
		}
		if (collected.crawlSkip() != null) {
			gone.add(new Gone(collectionId, serviceId, "not crawled: " + collected.crawlSkip()));
			return;
		}
		if (collected.lastFullSuccessAt() == null) {
			changed.add(new Change(collectionId, serviceId, "crawl", "crawled", "no full success"));
		}
		String baseUrl = rules.finalOrigins().getOrDefault(collected.baseUrl(), collected.baseUrl());
		if (!RuleSet.stripSlash(baseUrl).equals(RuleSet.stripSlash(String.valueOf(service.baseUrl())))) {
			changed.add(new Change(collectionId, serviceId, "base_url", service.baseUrl(), baseUrl));
		}
		String name = collected.name();
		RuleSet.NameOverride override = rules.nameOverrides().get(serviceId);
		if (override != null && name.equals(override.oldName())) {
			name = override.newName();
		}
		name = HtmlUtils.htmlUnescape(name);
		if (!name.equals(service.name())) {
			changed.add(new Change(collectionId, serviceId, "name", service.name(), name));
		}
		String platform = service.platformType() == null ? null : service.platformType().name();
		if (!Objects.equals(collected.platformType(), platform)) {
			changed.add(new Change(collectionId, serviceId, "platform_type", platform, collected.platformType()));
			return;
		}
		if (VENDORS.containsKey(collected.platformType())) {
			LendingModel lending = lendingModel(collected);
			if (lending != service.lendingModel()) {
				changed.add(new Change(collectionId, serviceId, "lending_model", String.valueOf(service.lendingModel()),
						String.valueOf(lending)));
			}
		}
	}

	/**
	 * Host name without scheme, user, port and path, lower case (plan research 1-4: hosts may contain {@code _});
	 * empty when there is none. The rule lives in {@link SiteHosts} since cycle 18.
	 */
	static String host(String url) {
		String host = SiteHosts.host(url);
		return host == null ? "" : host;
	}

}
