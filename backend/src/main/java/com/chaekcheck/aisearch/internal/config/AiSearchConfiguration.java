package com.chaekcheck.aisearch.internal.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Binds {@link AiSearchProperties}. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AiSearchProperties.class)
class AiSearchConfiguration {
}
