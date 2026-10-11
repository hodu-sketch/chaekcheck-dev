package com.chaekcheck.catalog.internal;

import javax.sql.DataSource;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.chaekcheck.catalog.internal.search.BookSearch;
import com.chaekcheck.catalog.internal.search.PgBigmBookSearch;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CatalogLoadSettings.class)
class CatalogConfiguration {

	@Bean
	JdbcCatalogLoad catalogLoad(DataSource dataSource, CatalogLoadSettings settings) {
		return new JdbcCatalogLoad(dataSource, settings);
	}

	@Bean
	BookSearch bookSearch(JdbcClient jdbc) {
		return new PgBigmBookSearch(jdbc);
	}

}
