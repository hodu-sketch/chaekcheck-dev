import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.awt.geom.AffineTransform;
import java.awt.geom.PathIterator;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import javax.imageio.ImageIO;

/**
 * The letter logo (user request 2026-10-09: "'책' 이거 글자로 로고 만들어줘, png나 svg 둘다"): the letter 책 from the
 * site's own font (NanumBarunGothic Bold, OFL-1.1, converted from WOFF by woff_to_ttf.py) as a vector path.
 * Writes two shapes, each as SVG and PNG:
 *   chaekcheck-mark.*   white 책 on a rounded square in the logo purple (app icon, tab icon)
 *   chaekcheck-letter.* the purple 책 alone on a transparent background (beside the word mark)
 * Usage (repository root): java scripts/logo/BuildLetterLogo.java <font.ttf> <out dir> [<icon dir>]
 * <icon dir> (web/public): also writes favicon.svg, favicon-32.png and apple-touch-icon.png (180) from the mark.
 */
public class BuildLetterLogo {

	static final String PURPLE = "#6D57E9"; // the existing logo's purple (web/public/favicon.svg)

	static final int VIEW = 512;

	public static void main(String[] args) throws Exception {
		Font font = Font.createFont(Font.TRUETYPE_FONT, new File(args[0])).deriveFont(1000f);
		Path out = Path.of(args[1]);
		Files.createDirectories(out);
		GlyphVector glyph = font.createGlyphVector(new FontRenderContext(null, true, true), "책");
		Shape raw = glyph.getOutline();

		// mark: the letter fills 60% of the square, centred on its ink box
		Shape onTile = fit(raw, VIEW * 0.60);
		String tile = "<rect width=\"512\" height=\"512\" rx=\"112\" fill=\"" + PURPLE + "\"/>";
		write(out.resolve("chaekcheck-mark.svg"), tile + "<path fill=\"#FFFFFF\" d=\"" + svgPath(onTile) + "\"/>");
		// letter alone: fills 88% so it lines up with text beside it
		Shape alone = fit(raw, VIEW * 0.88);
		write(out.resolve("chaekcheck-letter.svg"), "<path fill=\"" + PURPLE + "\" d=\"" + svgPath(alone) + "\"/>");

		for (int size : new int[] { 512, 192, 64 }) {
			png(out.resolve("chaekcheck-mark-" + size + ".png"), size, onTile, true);
			png(out.resolve("chaekcheck-letter-" + size + ".png"), size, alone, false);
		}
		// tab and home-screen icons (user request 2026-10-09: "탭 아이콘도 새 책 로고로 바꿔줘"), when a third argument
		// names the folder of index.html's icons (web/public)
		if (args.length > 2) {
			Path icons = Path.of(args[2]);
			Files.copy(out.resolve("chaekcheck-mark.svg"), icons.resolve("favicon.svg"),
					java.nio.file.StandardCopyOption.REPLACE_EXISTING);
			png(icons.resolve("favicon-32.png"), 32, onTile, true);
			png(icons.resolve("apple-touch-icon.png"), 180, onTile, true);
		}
	}

	/** Scales the shape so its larger side is {@code target}, centred in the 512 box. */
	static Shape fit(Shape shape, double target) {
		Rectangle2D b = shape.getBounds2D();
		double scale = target / Math.max(b.getWidth(), b.getHeight());
		AffineTransform t = new AffineTransform();
		t.translate(VIEW / 2.0, VIEW / 2.0);
		t.scale(scale, scale);
		t.translate(-b.getCenterX(), -b.getCenterY());
		return t.createTransformedShape(shape);
	}

	static String svgPath(Shape shape) {
		StringBuilder d = new StringBuilder();
		double[] c = new double[6];
		for (PathIterator it = shape.getPathIterator(null); !it.isDone(); it.next()) {
			switch (it.currentSegment(c)) {
				case PathIterator.SEG_MOVETO -> d.append('M').append(n(c[0])).append(' ').append(n(c[1]));
				case PathIterator.SEG_LINETO -> d.append('L').append(n(c[0])).append(' ').append(n(c[1]));
				case PathIterator.SEG_QUADTO -> d.append('Q').append(n(c[0])).append(' ').append(n(c[1])).append(' ')
					.append(n(c[2])).append(' ').append(n(c[3]));
				case PathIterator.SEG_CUBICTO -> d.append('C').append(n(c[0])).append(' ').append(n(c[1])).append(' ')
					.append(n(c[2])).append(' ').append(n(c[3])).append(' ').append(n(c[4])).append(' ').append(n(c[5]));
				case PathIterator.SEG_CLOSE -> d.append('Z');
				default -> throw new IllegalStateException();
			}
		}
		return d.toString();
	}

	static String n(double v) {
		return String.format(Locale.ROOT, "%.1f", v).replace(".0", "");
	}

	static void write(Path file, String body) throws Exception {
		Files.writeString(file, "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 512 512\">" + body + "</svg>\n",
				StandardCharsets.UTF_8);
	}

	static void png(Path file, int size, Shape letter, boolean tile) throws Exception {
		BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = image.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
		g.scale(size / (double) VIEW, size / (double) VIEW);
		Color purple = Color.decode(PURPLE);
		if (tile) {
			g.setColor(purple);
			g.fill(new RoundRectangle2D.Double(0, 0, VIEW, VIEW, 224, 224));
			g.setColor(Color.WHITE);
		}
		else {
			g.setColor(purple);
		}
		g.fill(letter);
		g.dispose();
		ImageIO.write(image, "png", file.toFile());
	}

}
