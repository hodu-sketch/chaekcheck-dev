import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.imageio.ImageIO;

/**
 * Makes the library logo files the web serves (plan ADR-0059, handoff step 12): every {@code status=OWN} row of the
 * registered logo table, shrunk to about 48px high (twice the shown height), same format; svg is copied as is. No
 * crop, colour or shape change. Also writes the Liquibase CSV of logo file names.
 *
 * <pre>
 * java scripts/library/BuildLogos.java ../chaekcheck-data/logos/registered-logos-2026-10-05.tsv
 * </pre>
 * The table's {@code file} column is relative to its parent folder ({@code logos/1.gif}). Writes
 * {@code web/public/library-logos/{library_id}.{ext}} and
 * {@code backend/src/main/resources/db/changelog/library/009-library-logos.csv}; prints the total size and the
 * files that are not images (left out, so the library shows the empty box).
 * Run from the repository root.
 */
public class BuildLogos {

	static final int HEIGHT = 48;

	static final Path OUT = Path.of("web/public/library-logos");

	static final Path CSV = Path.of("backend/src/main/resources/db/changelog/library/009-library-logos.csv");

	public static void main(String[] args) throws IOException {
		if (args.length != 1) {
			throw new IllegalArgumentException("usage: java scripts/library/BuildLogos.java <registered-logos.tsv>");
		}
		Path table = Path.of(args[0]);
		Path base = table.toAbsolutePath().getParent().getParent();
		List<String> lines = Files.readAllLines(table, StandardCharsets.UTF_8);
		List<String> header = List.of(lines.getFirst().split("\t", -1));
		int idColumn = header.indexOf("library_id");
		int statusColumn = header.indexOf("status");
		int fileColumn = header.indexOf("file");
		Files.createDirectories(OUT);
		List<String> csv = new ArrayList<>(List.of("library_id,logo_file"));
		long total = 0;
		int shrunk = 0;
		for (String line : lines.subList(1, lines.size())) {
			String[] cols = line.split("\t", -1);
			if (!"OWN".equals(cols[statusColumn])) {
				continue;
			}
			long id = Long.parseLong(cols[idColumn]);
			Path source = base.resolve(cols[fileColumn]);
			String ext = cols[fileColumn].substring(cols[fileColumn].lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
			String name = id + "." + ext;
			Path target = OUT.resolve(name);
			if (ext.equals("svg")) {
				Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
			}
			else {
				BufferedImage image = ImageIO.read(source.toFile());
				if (image == null) {
					// e.g. an error page saved under the logo name: no logo, the screen shows the empty box
					System.out.println("not an image, left out: " + id + " " + source.getFileName());
					continue;
				}
				if (image.getHeight() > HEIGHT) {
					image = shrink(image, ext);
					shrunk++;
					if (!ImageIO.write(image, ext.equals("jpg") ? "jpeg" : ext, target.toFile())) {
						throw new IOException("no writer for " + ext);
					}
				}
				else {
					Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
				}
			}
			total += Files.size(target);
			csv.add(id + "," + name);
		}
		Files.write(CSV, csv, StandardCharsets.UTF_8);
		System.out.printf("%d logos (%d shrunk to %dpx high), %d bytes in %s%n", csv.size() - 1, shrunk, HEIGHT, total,
				OUT);
	}

	/** Halves the size step by step, then one last step to the height: smoother than one big bicubic step. */
	static BufferedImage shrink(BufferedImage image, String ext) {
		int type = ext.equals("jpg") || ext.equals("jpeg") ? BufferedImage.TYPE_INT_RGB : BufferedImage.TYPE_INT_ARGB;
		int targetWidth = Math.max(1, Math.round(image.getWidth() * (float) HEIGHT / image.getHeight()));
		BufferedImage current = image;
		int width = image.getWidth();
		int height = image.getHeight();
		do {
			width = Math.max(targetWidth, width / 2);
			height = Math.max(HEIGHT, height / 2);
			BufferedImage next = new BufferedImage(width, height, type);
			Graphics2D g = next.createGraphics();
			g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
			g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
			g.drawImage(current, 0, 0, width, height, null);
			g.dispose();
			current = next;
		}
		while (width != targetWidth || height != HEIGHT);
		return current;
	}

}
