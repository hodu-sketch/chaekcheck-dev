package com.chaekcheck.usagelog.internal;

import java.time.Clock;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.chaekcheck.auth.LoginStatusReader;
import com.chaekcheck.library.LibraryCatalog;
import com.chaekcheck.usagelog.internal.buffer.UsageBuffer;
import com.chaekcheck.usagelog.internal.write.UsageLogFlusher;
import com.chaekcheck.usagelog.internal.write.UsageLogWriter;

/**
 * Beans of the usage log. The writes run on the flusher's own thread; only the daily clean-up uses Spring's shared
 * scheduler (switched on here too, as in the picks module).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(UsageLogProperties.class)
@EnableScheduling
class UsageLogConfiguration {

	@Bean
	UsageBuffer usageBuffer(UsageLogProperties properties) {
		return new UsageBuffer(properties.bufferCapacity());
	}

	@Bean
	DefaultUsageLog usageLog(UsageLogProperties properties, UsageBuffer buffer, LoginStatusReader loginStatus) {
		return new DefaultUsageLog(properties.enabled(), buffer, loginStatus, Clock.systemUTC(),
				properties.cleanup().zone());
	}

	@Bean
	UsageLogWriter usageLogWriter(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
		return new UsageLogWriter(jdbc, new TransactionTemplate(transactionManager));
	}

	@Bean
	UsageLogFlusher usageLogFlusher(UsageLogProperties properties, UsageBuffer buffer, UsageLogWriter writer,
			LibraryCatalog libraryCatalog) {
		return new UsageLogFlusher(buffer, writer, libraryCatalog, Clock.systemUTC(), properties.flushInterval(),
				properties.maxDroppedNotes(), properties.enabled());
	}

}
