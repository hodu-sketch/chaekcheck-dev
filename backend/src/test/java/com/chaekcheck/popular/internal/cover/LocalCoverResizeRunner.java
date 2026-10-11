package com.chaekcheck.popular.internal.cover;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * One-off, local development only (cycle 10, 2026-10-12): makes the covers stored at their original size by the first
 * real fetch one size, as a fetch now stores them (200px JPEG), without calling the site again. Never runs in the
 * build: only with the environment variable {@code CHAEKCHECK_RESIZE_LOCAL_COVERS=true}, against the local
 * {@code db} named in {@code ../.env} (DB_PORT, DB_NAME, DB_USERNAME, DB_PASSWORD):
 *
 * <pre>
 * CHAEKCHECK_RESIZE_LOCAL_COVERS=true ./gradlew test --tests '*LocalCoverResizeRunner' --rerun
 * </pre>
 *
 * Rows already JPEG and at most 200px wide are left alone, so running it twice changes nothing. Rows no image reader
 * can read are left as they are and counted. Cover ids stay the same.
 */
@EnabledIfEnvironmentVariable(named = "CHAEKCHECK_RESIZE_LOCAL_COVERS", matches = "true")
class LocalCoverResizeRunner {

	static final int WIDTH = 200;

	@Test
	void resizeStoredCovers() throws IOException, SQLException {
		Map<String, String> env = dotEnv(Path.of("..", ".env"));
		String url = "jdbc:postgresql://localhost:" + env.getOrDefault("DB_PORT", "5432") + "/" + env.get("DB_NAME");
		CoverResizer resizer = new CoverResizer(WIDTH, 0.8f, 16_000_000);
		int resized = 0;
		int skipped = 0;
		int unreadable = 0;
		long before = 0;
		long after = 0;
		try (Connection db = DriverManager.getConnection(url, env.get("DB_USERNAME"), env.get("DB_PASSWORD"))) {
			List<Long> ids = new ArrayList<>();
			try (PreparedStatement select = db.prepareStatement("SELECT id FROM popular.cover ORDER BY id");
					ResultSet rows = select.executeQuery()) {
				while (rows.next()) {
					ids.add(rows.getLong(1));
				}
			}
			for (long id : ids) {
				String type;
				byte[] image;
				try (PreparedStatement one = db.prepareStatement("SELECT content_type, image FROM popular.cover WHERE id = ?")) {
					one.setLong(1, id);
					try (ResultSet row = one.executeQuery()) {
						if (!row.next()) {
							continue;
						}
						type = row.getString(1);
						image = row.getBytes(2);
					}
				}
				if (CoverResizer.CONTENT_TYPE.equals(type) && width(image) <= WIDTH) {
					skipped++;
					continue;
				}
				Optional<byte[]> jpeg = resizer.toJpeg(image);
				if (jpeg.isEmpty()) {
					unreadable++;
					System.out.println("cover " + id + " not readable, left as it is");
					continue;
				}
				try (PreparedStatement update = db.prepareStatement(
						"UPDATE popular.cover SET content_type = ?, image = ?, byte_size = ? WHERE id = ?")) {
					update.setString(1, CoverResizer.CONTENT_TYPE);
					update.setBytes(2, jpeg.get());
					update.setInt(3, jpeg.get().length);
					update.setLong(4, id);
					update.executeUpdate();
				}
				resized++;
				before += image.length;
				after += jpeg.get().length;
			}
		}
		System.out.printf("covers resized %d (%d -> %d bytes), already one size %d, not readable %d%n", resized, before,
				after, skipped, unreadable);
	}

	/** Width from the image header, or {@link Integer#MAX_VALUE} when no reader knows it. */
	private static int width(byte[] image) throws IOException {
		try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(image))) {
			Iterator<ImageReader> readers = input == null ? null : ImageIO.getImageReaders(input);
			if (readers == null || !readers.hasNext()) {
				return Integer.MAX_VALUE;
			}
			ImageReader reader = readers.next();
			try {
				reader.setInput(input, true, true);
				return reader.getWidth(0);
			}
			finally {
				reader.dispose();
			}
		}
	}

	private static Map<String, String> dotEnv(Path file) throws IOException {
		Map<String, String> values = new HashMap<>();
		for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
			String trimmed = line.strip();
			int equals = trimmed.indexOf('=');
			if (trimmed.isEmpty() || trimmed.startsWith("#") || equals < 0) {
				continue;
			}
			values.put(trimmed.substring(0, equals).strip(), trimmed.substring(equals + 1).strip());
		}
		values.putAll(System.getenv().entrySet()
			.stream()
			.filter(entry -> entry.getKey().startsWith("DB_"))
			.collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)));
		return values;
	}

}
