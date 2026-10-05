package com.chaekcheck.availability.internal.search;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.chaekcheck.availability.internal.TestTargets;
import com.chaekcheck.catalog.HoldingRef;
import com.chaekcheck.library.LendingModel;
import com.chaekcheck.library.PlatformType;
import com.chaekcheck.library.SearchTarget;

/** Cycle 07 (ADR-0056, AC-23): an owned-copies institution on the subscription screen reads its book pages. */
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

	private static SearchTarget target(LendingModel model) {
		SearchTarget t = TestTargets.target(19, PlatformType.KYOBO_SUBSCRIPTION_V1, BASE);
		return new SearchTarget(t.id(), t.name(), t.libraryType(), t.platformType(), t.baseUrl(), t.priorityGroup(),
				t.deferred(), t.eligibility(), t.eligibilitySummary(), t.extraConditionText(), t.visitRequired(),
				t.joinMethod(), t.joinUrl(), t.joinCaution(), t.joinNotice(), t.homepageUrl(), t.chosen(),
				t.joinSurvey(), model, t.logoUrl());
	}

}
