package com.chaekcheck.aisearch.internal.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The second step's ask-back (user 2026-10-26 「되묻기만 기존 한 번의 호출에」, after the agent test in
 * docs/features/aisearch/reports/2026-10-26-agent.md): the pick call may ask the user back with a few choices instead of
 * picking, through an {@code ask} field first in its JSON; a question the first step calls not a book request gets a
 * fixed ask-back with examples, and one word it calls so goes to the pick call. Off by default.
 * @param askBack whether the pick call may ask back
 * @param askOneWord whether a question of one word (「사진」) may be asked back too, as it was from v1.17.0 to v1.20.5;
 *     off since the user's 2026-10-29 「한 단어도 그걸로 답변」: it is answered at once, the common senses mixed
 */
@ConfigurationProperties("chaekcheck.aisearch.pick")
public record PickProperties(boolean askBack, boolean askOneWord) {
}
