package com.chaekcheck.aisearch.internal.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Cycle 29: the reload request is taken only from inside the server (loopback or the Docker network). */
class SearchBooksReloadTests {

	@Test
	void onlyLoopbackAndPrivateAddressesMayAsk() {
		assertThat(SearchBooksReload.inside("127.0.0.1")).isTrue();
		assertThat(SearchBooksReload.inside("::1")).isTrue();
		assertThat(SearchBooksReload.inside("172.18.0.5")).isTrue();
		assertThat(SearchBooksReload.inside("10.0.0.2")).isTrue();
		assertThat(SearchBooksReload.inside("203.0.113.7")).isFalse();
		assertThat(SearchBooksReload.inside("8.8.8.8")).isFalse();
	}

}
