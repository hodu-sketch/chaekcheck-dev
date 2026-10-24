package com.chaekcheck.aisearch.internal.llm;

import java.net.URI;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The Anthropic Messages API settings (cycle 24 handoff step 6; docs/maintenance/settings-index.md). Checked
 * 2026-10-24 against https://platform.claude.com/docs/en/api/versioning (version 2026-10-21) and the structured outputs
 * and streaming pages.
 * @param apiKey a secret: environment only ({@code CHAEKCHECK_AISEARCH_ANTHROPIC_API_KEY}), never logged; empty means
 *     every call fails with {@code NOT_CONFIGURED} and the search takes its fallback way
 * @param model the model id ({@code claude-haiku-5-5} [확정])
 * @param baseUrl the API root
 * @param version the {@code anthropic-version} header
 * @param effort {@code output_config.effort} for every call (cycle 24 step 1: {@code low} [안]); empty sends none
 */
@ConfigurationProperties("chaekcheck.aisearch.llm.anthropic")
public record AnthropicProperties(String apiKey, String model, URI baseUrl, String version, String effort) {

	public AnthropicProperties {
		apiKey = apiKey == null ? "" : apiKey.strip();
		model = model == null || model.isBlank() ? "claude-haiku-5-5" : model;
		baseUrl = baseUrl == null ? URI.create("https://api.anthropic.com") : baseUrl;
		version = version == null || version.isBlank() ? "2026-10-21" : version;
		effort = effort == null ? "" : effort.strip();
		// the key goes to this address: https only, plain http only to this machine (tests) (step 8 security review)
		boolean loopback = "localhost".equals(baseUrl.getHost()) || "127.0.0.1".equals(baseUrl.getHost());
		if (!"https".equals(baseUrl.getScheme()) && !("http".equals(baseUrl.getScheme()) && loopback)) {
			throw new IllegalArgumentException("chaekcheck.aisearch.llm.anthropic.base-url must be an https address");
		}
	}

	boolean hasKey() {
		return !apiKey.isEmpty();
	}

	/** Never shows the key. */
	@Override
	public String toString() {
		return "AnthropicProperties[apiKey=" + (hasKey() ? "(set)" : "(empty)") + ", model=" + model + ", baseUrl="
				+ baseUrl + ", version=" + version + ", effort=" + effort + "]";
	}

}
