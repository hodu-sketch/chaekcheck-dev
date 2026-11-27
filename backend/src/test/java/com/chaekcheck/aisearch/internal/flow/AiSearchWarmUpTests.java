package com.chaekcheck.aisearch.internal.flow;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

/** The warm-up before the backend takes requests (user 2026-11-28 「데우기」): when it runs and that it never stops a start. */
class AiSearchWarmUpTests {

	private final List<String> asked = new ArrayList<>();

	@Test
	void everyQuestionRunsWithThePlanLibraries() {
		List<Set<Long>> libraries = new ArrayList<>();
		AiSearchWarmUp warmUp = new AiSearchWarmUp(() -> true, () -> Set.of(1L, 2L), (question, plan) -> {
			asked.add(question);
			libraries.add(plan);
			return "start=1 cards=2";
		});

		assertThat(warmUp.run()).isEqualTo(AiSearchWarmUp.QUESTIONS.size());

		assertThat(asked).isEqualTo(AiSearchWarmUp.QUESTIONS);
		assertThat(libraries).allSatisfy(plan -> assertThat(plan).containsExactlyInAnyOrder(1L, 2L));
	}

	@Test
	void offRunsNothing() {
		AiSearchWarmUp warmUp = new AiSearchWarmUp(() -> false, () -> {
			throw new AssertionError("the libraries are not read when off");
		}, (question, plan) -> {
			asked.add(question);
			return "";
		});

		assertThat(warmUp.run()).isZero();
		assertThat(asked).isEmpty();
	}

	@Test
	void aFailureStopsTheWarmUpAndNotTheStart() {
		AiSearchWarmUp warmUp = new AiSearchWarmUp(() -> true, Set::of, (question, plan) -> {
			asked.add(question);
			if (asked.size() == 2) {
				throw new IllegalStateException("the database is not there");
			}
			return "";
		});

		assertThat(warmUp.run()).isEqualTo(1);
		assertThat(asked).hasSize(2);
	}

}
