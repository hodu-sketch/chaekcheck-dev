package com.chaekcheck.aisearch.internal.flow;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The warm-up before the backend takes requests ({@link AiSearchWarmUp}; user 2026-11-28 「데우기」).
 * @param enabled whether the AI search's first questions are run once at start, before the readiness probe says
 * ready ({@code CHAEKCHECK_AISEARCH_WARM_ENABLED}, default on; tests turn it off)
 */
@ConfigurationProperties("chaekcheck.aisearch.warm")
record WarmProperties(boolean enabled) {
}
