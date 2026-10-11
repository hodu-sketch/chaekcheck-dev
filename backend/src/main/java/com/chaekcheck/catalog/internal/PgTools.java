package com.chaekcheck.catalog.internal;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * PostgreSQL 18's {@code pg_dump} and {@code pg_restore} (cycle 11, ADR-0078), run as a process: the configured
 * command prefix (e.g. {@code wsl -d Ubuntu --exec docker compose --project-directory <dir> exec -T db}, which runs
 * the tools inside the database container, so their version is the server's) followed by the tool and its
 * arguments. Files go through standard input and output, so the container needs no shared folder. The tools connect
 * through the container's local socket as the service's database user, without a password.
 */
class PgTools {

	/** {@code application_name} of the restore, to find a restore that outlived the Java process. */
	static final String RESTORE_APPLICATION = "chaekcheck-restore";

	private static final int ERROR_TAIL = 800;

	private final List<String> prefix;

	/** @param prefix the command before the tool name, one argument per element */
	PgTools(List<String> prefix) {
		if (prefix.isEmpty()) {
			throw new IllegalArgumentException("The pg command prefix is empty");
		}
		this.prefix = List.copyOf(prefix);
	}

	/** The database the tools work on: the one the service's connection is on. */
	record Database(String name, String user) {

		/** A libpq connection string for {@code -d} (https://www.postgresql.org/docs/18/libpq-connect.html). */
		String connection(String application) {
			return "dbname=" + name + " application_name=" + application;
		}

	}

	/** {@code catalog_next} in custom format, without owners and privileges, into {@code file}. */
	void dump(Database database, Path file) {
		run(command("pg_dump", "-Fc", "-n", JdbcCatalogLoad.NEXT, "--no-owner", "--no-privileges", "-U",
				database.user(), "-d", database.connection("chaekcheck-dump")), null, file);
	}

	/**
	 * Restores {@code file} in one transaction, stopping at the first error. Java writes the file into the tool's
	 * standard input itself: when the Java process dies, the input ends and the restore rolls back. With the file
	 * handed to the child process instead, the restore went on and committed (cycle 11 prototype 4).
	 */
	void restore(Database database, Path file) {
		run(command("pg_restore", "--single-transaction", "--exit-on-error", "--no-owner", "--no-privileges", "-U",
				database.user(), "-d", database.connection(RESTORE_APPLICATION)), file, null);
	}

	/**
	 * The definition of {@code catalog_next.load_manifest} in {@code file}, with its comment (no database needed).
	 * Only the file's table of contents is read.
	 */
	String manifestDefinition(Path file) {
		try {
			Path out = Files.createTempFile("load-manifest", ".sql");
			try {
				run(command("pg_restore", "-s", "-n", JdbcCatalogLoad.NEXT, "-t", "load_manifest", "-f", "-"), file, out);
				return Files.readString(out, StandardCharsets.UTF_8);
			}
			finally {
				Files.deleteIfExists(out);
			}
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	private List<String> command(String... tool) {
		List<String> command = new ArrayList<>(prefix);
		command.addAll(List.of(tool));
		return command;
	}

	/**
	 * Runs one tool; {@code input} is written into its standard input by this process. Standard error goes to a
	 * temporary file; when the tool fails its end is in the exception message (kept as the import's
	 * {@code failure_detail}). The exit code decides: a tool may stop reading early (a manifest read, an error).
	 */
	private static void run(List<String> command, Path input, Path output) {
		String tool = command.stream().filter(a -> a.startsWith("pg_")).findFirst().orElse(command.getFirst());
		Path errors = null;
		try {
			errors = Files.createTempFile("pg-tool", ".err");
			ProcessBuilder builder = new ProcessBuilder(command).redirectError(errors.toFile());
			builder.redirectOutput(output == null ? ProcessBuilder.Redirect.DISCARD
					: ProcessBuilder.Redirect.to(output.toFile()));
			Process process = builder.start();
			try (OutputStream stdin = process.getOutputStream()) {
				if (input != null) {
					try (InputStream in = Files.newInputStream(input)) {
						in.transferTo(stdin);
					}
					catch (IOException ex) {
						// the tool closed its input; its exit code says whether that was fine
					}
				}
			}
			catch (IOException ex) {
				// as above
			}
			int exit = process.waitFor();
			if (exit != 0) {
				String text = Files.readString(errors, StandardCharsets.UTF_8).strip();
				throw new IllegalStateException(tool + " failed (exit " + exit + "): "
						+ text.substring(Math.max(0, text.length() - ERROR_TAIL)));
			}
		}
		catch (IOException ex) {
			throw new UncheckedIOException(tool + " could not run", ex);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(tool + " was interrupted", ex);
		}
		finally {
			if (errors != null) {
				try {
					Files.deleteIfExists(errors);
				}
				catch (IOException ex) {
					// a temporary file left behind is harmless
				}
			}
		}
	}

}
