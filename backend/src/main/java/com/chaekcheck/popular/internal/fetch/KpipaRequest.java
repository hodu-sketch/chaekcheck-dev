package com.chaekcheck.popular.internal.fetch;

import java.util.Map;

/**
 * One request to the site.
 * @param path absolute path on the site, starting with {@code /}
 * @param headers besides the User-Agent, which every request carries
 * @param form {@code application/x-www-form-urlencoded} body of a POST; null for a GET
 * @param cover a cover image: read with the cover body limit, not the page limit
 */
public record KpipaRequest(String method, String path, Map<String, String> headers, String form, boolean cover) {

	public KpipaRequest {
		headers = Map.copyOf(headers);
	}

	public static KpipaRequest get(String path, Map<String, String> headers) {
		return new KpipaRequest("GET", path, headers, null, false);
	}

	public static KpipaRequest post(String path, Map<String, String> headers, String form) {
		return new KpipaRequest("POST", path, headers, form, false);
	}

	public static KpipaRequest cover(String path) {
		return new KpipaRequest("GET", path, Map.of(), null, true);
	}

}
