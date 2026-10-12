package com.chaekcheck.picks.internal.fetch;

/** An answer. Redirects are not followed, so a 302 arrives as it is. */
public record NlkResponse(int status, String contentType, byte[] body) {
}
