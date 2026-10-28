package com.chaekcheck.aisearch.internal.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The second step's ask-back (user 2026-10-26 「되묻기만 기존 한 번의 호출에」, after the agent test in
 * docs/aisearch/reports/2026-10-26-agent.md): the pick call may ask the user back with a few choices instead of
 * picking, through an {@code ask} field first in its JSON; a question the first step calls not a book request gets a
 * fixed ask-back with examples, and one word it calls so goes to the pick call. Off by default.
 * @param askBack whether the pick call may ask back
 */
@ConfigurationProperties("chaekcheck.aisearch.pick")
public record PickProperties(boolean askBack) {
}
