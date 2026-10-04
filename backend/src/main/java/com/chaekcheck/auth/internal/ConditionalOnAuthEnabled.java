package com.chaekcheck.auth.internal;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;

/**
 * Put on every bean of the login feature (configurations, controllers, jobs): component scanning finds them all,
 * so each one must stay out when {@code chaekcheck.auth.enabled} is off (ADR-0046).
 */
@Target({ ElementType.TYPE, ElementType.METHOD })
@Retention(RetentionPolicy.RUNTIME)
@Documented
@ConditionalOnBooleanProperty(AuthToggleEnvironmentPostProcessor.ENABLED)
public @interface ConditionalOnAuthEnabled {

}
