package com.chaekcheck.availability.internal.search;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.chaekcheck.availability.internal.TestTargets;
import com.chaekcheck.catalog.HoldingRef;
import com.chaekcheck.library.LendingModel;
import com.chaekcheck.library.PlatformType;
import com.chaekcheck.library.SearchTarget;

/**
 * Cycle 07 (ADR-0056, AC-23): an owned-copies institution on the subscription screen reads its book pages. Cycle 18:
 * underscore hosts (ADR-0111).
 */
class HoldingTaskTests {

	static final String BASE = "https://geoje.dkyobobook.co.kr";

	HoldingRef holding = new HoldingRef(1, 10, 19, "KYOBO_SUBSCRIPTION_V1",
			BASE + "/content/contentView.ink?cttsDvsnCode=001&brcd=4801130600605", "t", null, null);

	@Test
	void onlyOwnedSubscriptionScreensReadTheBookPage() {
		assertThat(HoldingTask.of(target(null), holding).subscription()).isTrue();
		assertThat(HoldingTask.of(target(LendingModel.MIXED), holding).subscription()).isTrue();
		HoldingTask owned = HoldingTask.of(target(LendingModel.OWNED), holding);
		assertThat(owned.subscription()).isFalse();
		assertThat(owned.detailUri()).isNotNull();
		assertThat(owned.hostKey()).isNotNull();
	}

	@Test
	void underscoreHostDetailPageOnTheSameHostIsRequested() { // cycle 18, 2624
		String base = "https://hs_partners.yes24library.com";
		SearchTarget target = TestTargets.target(2624, PlatformType.YES24_V2, base);
		HoldingRef sameHost = new HoldingRef(1, 10, 2624, "YES24_V2", base + "/ebook/detail/?goods_id=1", "t", null,
				null);
		HoldingRef otherHost = new HoldingRef(2, 11, 2624, "YES24_V2",
				"https://ebook.gangseo.seoul.kr/ebook/detail/?goods_id=1", "t", null, null);

		HoldingTask task = HoldingTask.of(target, sameHost);
		assertThat(task.detailUri()).hasToString(base + "/ebook/detail/?goods_id=1");
		assertThat(task.hostKey()).isEqualTo("hs_partners.yes24library.com");
		assertThat(HoldingTask.of(target, otherHost).detailUri()).isNull();
		assertThat(new Candidate(target, 1).hostKey()).isEqualTo("hs_partners.yes24library.com");
	}

	@Test
	void candidateWithoutAReadableHostKeepsItsOwnKey() { // cycle 18: no NullPointerException in the plan
		SearchTarget target = TestTargets.target(1, PlatformType.YES24_V2, "not a url");

		assertThat(new Candidate(target, 1).hostKey()).isEqualTo("not a url");
	}

	private static SearchTarget target(LendingModel model) {
		SearchTarget t = TestTargets.target(19, PlatformType.KYOBO_SUBSCRIPTION_V1, BASE);
		return new SearchTarget(t.id(), t.name(), t.libraryType(), t.platformType(), t.baseUrl(), t.priorityGroup(),
				t.deferred(), t.eligibility(), t.eligibilitySummary(), t.extraConditionText(), t.visitRequired(),
				t.joinMethod(), t.joinUrl(), t.joinCaution(), t.joinNotice(), t.homepageUrl(), t.chosen(),
				t.joinSurvey(), model, t.logoUrl(), t.mobileSiteDiffers(), t.vendor(),
				t.orderNo(), false);
	}

}
