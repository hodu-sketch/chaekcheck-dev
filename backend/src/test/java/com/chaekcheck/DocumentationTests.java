package com.chaekcheck;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;

/**
 * Writes the module diagrams (PlantUML, all modules and one per module) and the module canvases to
 * {@code build/spring-modulith-docs}, to compare with the plan's design pictures at the next cycle start.
 */
class DocumentationTests {

	ApplicationModules modules = ApplicationModules.of(ChaekcheckApplication.class);

	@Test
	void writeDocumentationSnippets() {
		new Documenter(modules).writeModulesAsPlantUml()
			.writeIndividualModulesAsPlantUml()
			.writeModuleCanvases();

		Path docs = Path.of("build", "spring-modulith-docs");
		assertThat(docs.resolve("components.puml")).exists();
		assertThat(Files.exists(docs.resolve("module-library.puml"))).isTrue();
		assertThat(Files.exists(docs.resolve("module-availability.adoc"))).isTrue();
		assertThat(Files.exists(docs.resolve("module-auth.puml"))).isTrue();
		assertThat(Files.exists(docs.resolve("module-mylibrary.puml"))).isTrue();
	}

}
