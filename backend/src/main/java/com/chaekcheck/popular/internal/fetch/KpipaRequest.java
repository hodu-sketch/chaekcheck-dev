package com.chaekcheck.popular.internal.fetch;

import java.util.Map;

/**
 * One request to the site.
 * @param path absolute path on the site, starting with {@code /}
 * @param headers besides the User-Agent, which every request carries
 * @param form {@code application/x-www-form-urlencoded} body of a POST; null for a GET
 */
public record KpipaRequest(String method, String path, Map<String, String> headers, String form) {

	public KpipaRequest {
		headers = Map.copyOf(headers);
	}

	public static KpipaRequest get(String path, Map<String, String> headers) {
		return new KpipaRequest("GET", path, headers, null);
	}

	public static KpipaRequest post(String path, Map<String, String> headers, String form) {
		return new KpipaRequest("POST", path, headers, form);
	}

}
