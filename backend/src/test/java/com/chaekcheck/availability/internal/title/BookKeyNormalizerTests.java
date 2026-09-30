package com.chaekcheck.availability.internal.title;

import static org.assertj.core.api.Assertions.assertThat;

import java.text.Normalizer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class BookKeyNormalizerTests {

	BookKeyNormalizer normalizer = new BookKeyNormalizer();

	@Test
	void spacingDoesNotMatter() { // AC-23
		assertThat(normalizer.key("채식주의자")).isEqualTo(normalizer.key("채식 주의자"))
			.isEqualTo(normalizer.key("  채식주의자 "))
			.isEqualTo("채식주의자");
	}

	@Test
	void decomposedHangulIsTheSameKey() {
		String nfd = Normalizer.normalize("채식주의자", Normalizer.Form.NFD);

		assertThat(nfd).isNotEqualTo("채식주의자");
		assertThat(normalizer.key(nfd)).isEqualTo("채식주의자");
	}

	@ParameterizedTest
	@CsvSource(delimiter = '|', value = { "THE 궁합, 사주 사랑이 운명이 되다 | the궁합사주사랑이운명이되다",
			"Harry Potter: 1 | harrypotter1", "\"사랑\"에 대한 작은 책 | 사랑에대한작은책",
			"한강, 채식주의자 깊게 읽기 | 한강채식주의자깊게읽기", "사랑 후에 오는 것들 (공지영) | 사랑후에오는것들공지영" })
	void punctuationAndCaseAreIgnored(String title, String key) {
		assertThat(normalizer.key(title)).isEqualTo(key);
	}

	@Test
	void volumesStayDifferent() {
		assertThat(normalizer.key("신 퇴마록 신세편 1")).isNotEqualTo(normalizer.key("신 퇴마록 신세편 2"));
	}

	@Test
	void nullTitleIsEmptyKey() {
		assertThat(normalizer.key(null)).isEmpty();
	}

}
