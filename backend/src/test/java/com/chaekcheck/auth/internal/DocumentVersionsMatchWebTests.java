package com.chaekcheck.auth.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * C27: the terms and privacy pages show the versions in {@code web/src/features/auth/documentVersions.json}; sign-up
 * checks against {@code chaekcheck.auth.documents} in {@code application.yml}. Both must say the same, so changing
 * one without the other fails the build (dev choice, handoff step 15).
 */
class DocumentVersionsMatchWebTests {

	private static final Path WEB_FILE = Path.of("..", "web", "src", "features", "auth", "documentVersions.json");

	@Test
	void webPagesShowTheServerVersions() throws Exception {
		YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
		yaml.setResources(new ClassPathResource("application.yml"));
		Properties server = yaml.getObject();
		JsonNode web = JsonMapper.builder().build().readTree(Files.readString(WEB_FILE));

		assertThat(web.get("termsOfService").asString())
			.isEqualTo(server.getProperty("chaekcheck.auth.documents.terms-of-service-version"));
		assertThat(web.get("privacyCollection").asString())
			.isEqualTo(server.getProperty("chaekcheck.auth.documents.privacy-collection-version"));
	}

}
