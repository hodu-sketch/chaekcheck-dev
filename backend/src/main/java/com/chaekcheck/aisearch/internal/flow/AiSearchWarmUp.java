package com.chaekcheck.aisearch.internal.flow;

import java.util.List;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import com.chaekcheck.aisearch.internal.config.AiSearchProperties;
import com.chaekcheck.library.LibraryCatalog;
import com.chaekcheck.library.SearchTarget;

/**
 * Runs a few first questions through the AI search before the backend takes requests (user 2026-11-28 「데우기」,
 * docs/features/aisearch/1-how-it-works.md 7). Production 2026-11-28, right after a restart: the first search took
 * 1,461 ms to its cards and 3,451 ms to the user's first text, the second 560 ms and 2,110 ms, the third and later
 * 376 to 480 ms; the time was spread over every step (start, question vector, ML first call, nearest books, the
 * named-book and title scan, cards), the code and the queries running for the first time.
 * <p>
 * An {@link ApplicationRunner}: Spring Boot says ready (the readiness probe {@code bin/switch.sh} waits for) only after
 * the runners, so the deploy sends no visitor to this backend before it is warm. Each run goes up to the cards and
 * stops before Haiku ({@link AiSearchFlow#warm}); the embedding server is called once per question, at start only
 * (ADR-0174 5 keeps it from being kept warm while idle). A failure is logged and never stops the start.
 */
@Component
@EnableConfigurationProperties(WarmProperties.class)
class AiSearchWarmUp implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(AiSearchWarmUp.class);

	/**
	 * First questions of different kinds, run in this order. Production 2026-11-28: the second search still took 200 ms
	 * more in {@code nearest} than later ones, the third was as fast as any, so three [안].
	 */
	static final List<String> QUESTIONS = List.of("요즘 읽기 좋은 소설 추천해줘", "파이썬 처음 배우는 사람을 위한 책",
			"마음이 힘들 때 위로가 되는 에세이");

	private final BooleanSupplier on;

	private final Supplier<Set<Long>> planLibraries;

	private final BiFunction<String, Set<Long>, String> warm;

	@Autowired
	AiSearchWarmUp(WarmProperties warm, AiSearchProperties properties, LibraryCatalog libraries, AiSearchFlow flow) {
		this(() -> warm.enabled() && properties.enabled(), () -> planLibraries(libraries), flow::warm);
	}

	AiSearchWarmUp(BooleanSupplier on, Supplier<Set<Long>> planLibraries, BiFunction<String, Set<Long>, String> warm) {
		this.on = on;
		this.planLibraries = planLibraries;
		this.warm = warm;
	}

	/** Every library a plan without regions holds: the keyword search reads the same index a visitor's does. */
	private static Set<Long> planLibraries(LibraryCatalog libraries) {
		return Set.copyOf(libraries.planSearch(Set.of(), Set.of()).stream().map(SearchTarget::id).toList());
	}

	@Override
	public void run(ApplicationArguments args) {
		run();
	}

	/** @return how many questions ran to the end, for the tests */
	int run() {
		if (!on.getAsBoolean()) {
			return 0;
		}
		long started = System.nanoTime();
		int done = 0;
		try {
			Set<Long> libraries = planLibraries.get();
			for (int i = 0; i < QUESTIONS.size(); i++) {
				long one = System.nanoTime();
				String steps = warm.apply(QUESTIONS.get(i), libraries);
				log.info("ai search warm-up {}/{}: {} ms, steps {}", i + 1, QUESTIONS.size(),
						(System.nanoTime() - one) / 1_000_000, steps);
				done++;
			}
		}
		catch (RuntimeException e) {
			log.warn("ai search warm-up stopped after {} of {}: {}: {}", done, QUESTIONS.size(), e.getClass().getName(),
					e.getMessage());
		}
		log.info("ai search warm-up: {} of {} in {} ms", done, QUESTIONS.size(), (System.nanoTime() - started) / 1_000_000);
		return done;
	}

}
