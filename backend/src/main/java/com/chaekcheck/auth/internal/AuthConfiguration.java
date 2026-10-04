package com.chaekcheck.auth.internal;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Everything of the login feature hangs off this configuration; it exists only when login is on (ADR-0046). */
@Configuration(proxyBeanMethods = false)
@ConditionalOnBooleanProperty(AuthToggleEnvironmentPostProcessor.ENABLED)
@EnableConfigurationProperties(AuthProperties.class)
class AuthConfiguration {

}
