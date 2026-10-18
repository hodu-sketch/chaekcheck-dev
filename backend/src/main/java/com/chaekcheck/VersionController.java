package com.chaekcheck;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/v1/version}: the running release and the commit it was built from (user decision 2026-10-18:
 * semantic versions from v1.0.0, with the commit hash). Read from {@code META-INF/build-info.properties}, which
 * {@code bootBuildInfo} writes (backend/build.gradle); without that file both are "unknown". No login.
 * Not a module: this is the application root package, like {@link ApiExceptionHandler}.
 */
@RestController
class VersionController {

	private static final String UNKNOWN = "unknown";

	private final Version version;

	VersionController(ObjectProvider<BuildProperties> build) {
		BuildProperties properties = build.getIfAvailable();
		this.version = properties == null ? new Version(UNKNOWN, UNKNOWN)
				: new Version(orUnknown(properties.getVersion()), orUnknown(properties.get("commit")));
	}

	@GetMapping("/api/v1/version")
	Version version() {
		return version;
	}

	private static String orUnknown(String value) {
		return value == null || value.isBlank() ? UNKNOWN : value;
	}

	/** @param commit the short git hash of the build ("unknown" when git was not there) */
	record Version(String version, String commit) {
	}

}
