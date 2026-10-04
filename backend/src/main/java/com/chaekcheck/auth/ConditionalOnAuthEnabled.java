package com.chaekcheck.auth;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;

/**
 * Put on every bean of the login feature (configurations, controllers, jobs): component scanning finds them all,
 * so each one must stay out when {@code chaekcheck.auth.enabled} is off (ADR-0046). Public since cycle 06 so that
 * {@code mylibrary}, whose endpoints need a signed-in account, follows the same switch.
 */
@Target({ ElementType.TYPE, ElementType.METHOD })
@Retention(RetentionPolicy.RUNTIME)
@Documented
@ConditionalOnBooleanProperty(ConditionalOnAuthEnabled.PROPERTY)
public @interface ConditionalOnAuthEnabled {

	/** The switch for the whole login feature, default off. */
	String PROPERTY = "chaekcheck.auth.enabled";

}
