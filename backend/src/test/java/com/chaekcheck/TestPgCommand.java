package com.chaekcheck;

import java.util.List;

/**
 * The command prefix that runs PostgreSQL 18's tools in the db-test container (cycle 11 export and apply tests):
 * {@code TEST_PG_COMMAND} (comma separated) when set, else the db-test service of the compose project
 * {@code chaekcheck} through WSL (the Windows PC, docs/maintenance/3-import.md 13).
 */
public final class TestPgCommand {

	private TestPgCommand() {
	}

	public static List<String> get() {
		String configured = System.getenv("TEST_PG_COMMAND");
		if (configured != null && !configured.isBlank()) {
			return List.of(configured.split(","));
		}
		return List.of("wsl", "-d", "Ubuntu", "--exec", "docker", "compose", "-p", "chaekcheck", "exec", "-T", "db-test");
	}

}
