package com.chaekcheck.popular.internal.cover;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.Optional;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Makes every cover one size (user decision 2026-10-12, "표지 크기를 하나로 맞춤"): {@code width} pixels wide, the
 * height in proportion, as JPEG. Narrower images keep their width and are only re-encoded; transparency (PNG, GIF) is
 * put on white. JDK {@code javax.imageio} and {@code java.awt} only, off-screen (Spring Boot runs with
 * {@code java.awt.headless=true}). The image size is read from its header first: one over {@code maxPixels} is not
 * decoded, so a huge image cannot fill the heap. Thread-safe.
 */
public final class CoverResizer {

	/** Stored and served type of every cover. */
	public static final String CONTENT_TYPE = "image/jpeg";

	private static final Logger log = LoggerFactory.getLogger(CoverResizer.class);

	private final int width;

	private final float jpegQuality;

	private final long maxPixels;

	public CoverResizer(int width, float jpegQuality, long maxPixels) {
		this.width = width;
		this.jpegQuality = jpegQuality;
		this.maxPixels = maxPixels;
	}

	/**
	 * @return the JPEG bytes, or empty when ImageIO cannot read the bytes as an image or it is over {@code maxPixels}
	 */
	public Optional<byte[]> toJpeg(byte[] original) {
		try {
			BufferedImage image = read(original);
			if (image == null) {
				return Optional.empty();
			}
			return Optional.of(write(scale(image)));
		}
		catch (IOException | RuntimeException ex) {
			log.warn("cover image not readable: {}", ex.toString());
			return Optional.empty();
		}
	}

	private BufferedImage read(byte[] bytes) throws IOException {
		try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
			if (input == null) {
				return null;
			}
			Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
			if (!readers.hasNext()) {
				return null;
			}
			ImageReader reader = readers.next();
			try {
				reader.setInput(input, true, true);
				long pixels = (long) reader.getWidth(0) * reader.getHeight(0);
				if (pixels <= 0 || pixels > maxPixels) {
					log.warn("cover image not decoded: {} x {} pixels", reader.getWidth(0), reader.getHeight(0));
					return null;
				}
				return reader.read(0);
			}
			finally {
				reader.dispose();
			}
		}
	}

	/** Halves while the image is at least twice too wide (keeps detail), then one bicubic step to the width. */
	private BufferedImage scale(BufferedImage image) {
		int targetWidth = Math.min(width, image.getWidth());
		int targetHeight = Math.max(1, (int) Math.round((double) image.getHeight() * targetWidth / image.getWidth()));
		BufferedImage current = image;
		int w = image.getWidth();
		int h = image.getHeight();
		while (w / 2 >= targetWidth) {
			w /= 2;
			h = Math.max(1, h / 2);
			current = draw(current, w, h);
		}
		return draw(current, targetWidth, targetHeight);
	}

	/** Draws on white, RGB without alpha (JPEG has none). */
	private static BufferedImage draw(BufferedImage source, int w, int h) {
		BufferedImage target = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = target.createGraphics();
		try {
			g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
			g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g.setColor(Color.WHITE);
			g.fillRect(0, 0, w, h);
			g.drawImage(source, 0, 0, w, h, null);
		}
		finally {
			g.dispose();
		}
		return target;
	}

	private byte[] write(BufferedImage image) throws IOException {
		ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try (ImageOutputStream output = ImageIO.createImageOutputStream(out)) {
			writer.setOutput(output);
			ImageWriteParam param = writer.getDefaultWriteParam();
			param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
			param.setCompressionQuality(jpegQuality);
			writer.write(null, new IIOImage(image, null, null), param);
		}
		finally {
			writer.dispose();
		}
		return out.toByteArray();
	}

}
