package com.chaekcheck.aisearch.internal.flow;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** {@link WholeText}: pairs stay, a surrogate alone becomes U+FFFD, other text is the same object. */
class WholeTextTests {

	@Test
	void onlyWholeCharactersRemain() {
		String plain = "책 찾기";
		assertThat(WholeText.of(plain)).isSameAs(plain);
		assertThat(WholeText.of("웃음 😀")).isEqualTo("웃음 😀");
		assertThat(WholeText.of("a\ud83db\ude00c\ud83d")).isEqualTo("a�b�c�");
	}

}
