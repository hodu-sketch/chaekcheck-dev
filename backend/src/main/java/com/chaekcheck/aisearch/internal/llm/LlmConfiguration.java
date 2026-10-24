package com.chaekcheck.aisearch.internal.llm;

import java.net.http.HttpClient;
import java.time.Duration;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import tools.jackson.databind.json.JsonMapper;

/** The AI search's language model client (cycle 24 handoff step 6). */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AnthropicProperties.class)
class LlmConfiguration {

	@Bean
	LlmClient llmClient(AnthropicProperties properties, JsonMapper json) {
		// a connection that is not open after 3 s will not finish the 5 s keyword call either (code constant [안])
		HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
		return new AnthropicLlmClient(properties, http, json);
	}

}
