package com.chaekcheck.catalog.internal;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.chaekcheck.catalog.internal.search.BookSearch;
import com.chaekcheck.catalog.internal.search.PgBigmBookSearch;

@Configuration(proxyBeanMethods = false)
class CatalogConfiguration {

	@Bean
	BookSearch bookSearch(JdbcClient jdbc) {
		return new PgBigmBookSearch(jdbc);
	}

}
