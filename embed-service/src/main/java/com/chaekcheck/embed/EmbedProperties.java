package com.chaekcheck.embed;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The embedding service's settings ({@code application.yml}).
 * @param modelDir the model folder ({@link QueryEncoder#MODEL}, {@link QueryEncoder#TOKENIZER})
 * @param threads threads of one model run
 * @param maxConcurrent model runs at once
 * @param token the shared secret of {@code X-Embed-Token}; empty refuses every request
 * @param maxChars longest text taken
 */
@ConfigurationProperties("embed")
public record EmbedProperties(String modelDir, int threads, int maxConcurrent, String token, int maxChars) {

	public EmbedProperties {
		modelDir = modelDir == null ? "" : modelDir;
		token = token == null ? "" : token;
	}

}
