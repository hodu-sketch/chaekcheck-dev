package com.chaekcheck.popular.internal;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Settings of the popular module. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PopularProperties.class)
class PopularConfiguration {

}
