package com.chaekcheck.picks;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The six field groups over the lists' own fields (user 2026-10-25). */
class FieldGroupsTests {

	@Test
	void eachListsFieldsComeToOneGroup() {
		assertThat(FieldGroups.of("kaist", "technology")).isEqualTo(FieldGroups.SCIENCE);
		assertThat(FieldGroups.of("leedongjin", "essay")).isEqualTo(FieldGroups.LITERATURE);
		assertThat(FieldGroups.of("librarian", "5")).isEqualTo(FieldGroups.SOCIETY);
		assertThat(FieldGroups.of("snu100", "western_thought")).isEqualTo(FieldGroups.HUMANITIES);
		assertThat(FieldGroups.of("nobel", "modern")).isEqualTo(FieldGroups.LITERATURE);
	}

	@Test
	void anUnknownFieldHasNoGroup() {
		assertThat(FieldGroups.of("kaist", "ai")).isNull();
		assertThat(FieldGroups.of("newlist", "x")).isNull();
	}

}
