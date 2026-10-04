package com.chaekcheck.auth.internal;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Binds and checks the login settings; exists only when login is on (ADR-0046). Also switches on {@code @Async} for
 * the {@code AccountDeleted} listeners ({@code @ApplicationModuleListener} runs after the commit, on its own thread);
 * no other module uses {@code @Async}.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnAuthEnabled
@EnableConfigurationProperties(AuthProperties.class)
@EnableAsync
class AuthConfiguration {

}
