package com.chaekcheck.aisearch.internal.config;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Where the AI searches run after their stream opened: one virtual thread each (the search's own pattern, step 0 #3).
 * How many run at once is held by {@code AiSearchSlots}, not here.
 */
@Configuration(proxyBeanMethods = false)
class AiSearchExecutorConfiguration {

	@Bean(destroyMethod = "close")
	ExecutorService aiSearchExecutor() {
		return Executors.newVirtualThreadPerTaskExecutor();
	}

}
