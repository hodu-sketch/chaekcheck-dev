package com.chaekcheck;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;

/** GET /api/v1/version (user decision 2026-10-18): the release version and the commit hash from build-info. */
class VersionControllerTests {

	@SuppressWarnings("unchecked")
	private static ObjectProvider<BuildProperties> provider(BuildProperties properties) {
		ObjectProvider<BuildProperties> provider = mock(ObjectProvider.class);
		given(provider.getIfAvailable()).willReturn(properties);
		return provider;
	}

	@Test
	void givesTheVersionAndTheCommitOfTheBuild() {
		Properties entries = new Properties();
		entries.setProperty("version", "1.0.0");
		entries.setProperty("commit", "68b529a");

		assertThat(new VersionController(provider(new BuildProperties(entries))).version())
			.isEqualTo(new VersionController.Version("1.0.0", "68b529a"));
	}

	@Test
	void saysUnknownWithoutBuildInfo() {
		assertThat(new VersionController(provider(null)).version())
			.isEqualTo(new VersionController.Version("unknown", "unknown"));
	}

}
