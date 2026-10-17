package com.chaekcheck.availability.internal.egress;

import java.io.IOException;

import com.chaekcheck.availability.internal.UpstreamException;
import com.chaekcheck.availability.internal.fetch.SiteResponse;

/**
 * Whether one outcome looks like the site (or the proxy) refusing this lane (ADR-0094: "연결이 대답 없이 끊기는 일이
 * 이어지면 멈춤"; the criterion is dev's): no answer at all (connect refused or timed out, read or total timeout,
 * connection reset), or HTTP 403, 407 (the proxy refused us: not authorized), 429 or 503. Every other outcome —
 * 2xx, redirects, other 4xx / 5xx, an answer over the body limit, no parse slot — is the site answering, so it is
 * not a signal. Five signals in a row pause the lane on that host ({@link BlockedLanes}).
 */
public final class BlockSignal {

	private BlockSignal() {
	}

	public static boolean of(SiteResponse response) {
		int status = response.status();
		return status == 403 || status == 407 || status == 429 || status == 503;
	}

	public static boolean of(UpstreamException ex) {
		if (ex.bodyTooLarge() || ex.code() != null) {
			return false;
		}
		if (ex.kind() == UpstreamException.Kind.TIMEOUT) {
			return true;
		}
		for (Throwable t = ex.getCause(); t != null; t = t.getCause()) {
			if (t instanceof IOException) {
				return true;
			}
		}
		return false;
	}

}
