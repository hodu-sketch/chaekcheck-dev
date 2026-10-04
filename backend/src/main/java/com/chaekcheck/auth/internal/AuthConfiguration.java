package com.chaekcheck.auth.internal;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Binds and checks the login settings; exists only when login is on (ADR-0046). */
@Configuration(proxyBeanMethods = false)
@ConditionalOnAuthEnabled
@EnableConfigurationProperties(AuthProperties.class)
class AuthConfiguration {

}
