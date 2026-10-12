package com.chaekcheck.picks.internal.cover;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

/**
 * Covers made one size (user decision 2026-10-12): 200px wide JPEG. The images are made here with ImageIO in the
 * shapes the first real fetch saw (PNG with transparency, GIF, wide and narrow JPEG), nothing is downloaded.
 */
class CoverResizerTests {

	final CoverResizer resizer = new CoverResizer(200, 0.8f, 16_000_000);

	@Test
	void wideJpegBecomes200Wide() {
		BufferedImage out = decode(resizer.toJpeg(image("jpeg", 600, 900, BufferedImage.TYPE_INT_RGB, Color.BLUE)).orElseThrow());

		assertThat(out.getWidth()).isEqualTo(200);
		assertThat(out.getHeight()).isEqualTo(300);
	}

	@Test
	void outputIsJpeg() throws IOException {
		byte[] jpeg = resizer.toJpeg(image("png", 458, 700, BufferedImage.TYPE_INT_RGB, Color.RED)).orElseThrow();

		assertThat(jpeg).startsWith((byte) 0xFF, (byte) 0xD8, (byte) 0xFF);
		var readers = ImageIO.getImageReaders(ImageIO.createImageInputStream(new ByteArrayInputStream(jpeg)));
		assertThat(readers.next().getFormatName()).isEqualToIgnoringCase("jpeg");
		assertThat(CoverResizer.CONTENT_TYPE).isEqualTo("image/jpeg");
	}

	@Test
	void transparentPngIsPutOnWhite() {
		byte[] png = image("png", 600, 900, BufferedImage.TYPE_INT_ARGB, new Color(0, 0, 0, 0));

		BufferedImage out = decode(resizer.toJpeg(png).orElseThrow());

		assertThat(out.getWidth()).isEqualTo(200);
		Color centre = new Color(out.getRGB(100, 150));
		assertThat(centre.getRed()).isGreaterThan(240);
		assertThat(centre.getGreen()).isGreaterThan(240);
		assertThat(centre.getBlue()).isGreaterThan(240);
	}

	@Test
	void gifIsRead() {
		byte[] gif = image("gif", 500, 750, BufferedImage.TYPE_BYTE_INDEXED, Color.GREEN);

		BufferedImage out = decode(resizer.toJpeg(gif).orElseThrow());

		assertThat(out.getWidth()).isEqualTo(200);
		assertThat(out.getHeight()).isEqualTo(300);
	}

	@Test
	void narrowImageKeepsItsSize() {
		BufferedImage out = decode(resizer.toJpeg(image("jpeg", 150, 225, BufferedImage.TYPE_INT_RGB, Color.GRAY)).orElseThrow());

		assertThat(out.getWidth()).isEqualTo(150);
		assertThat(out.getHeight()).isEqualTo(225);
	}

	@Test
	void notAnImageOrTooManyPixelsIsEmpty() {
		assertThat(resizer.toJpeg("<html>not an image</html>".getBytes(StandardCharsets.UTF_8))).isEmpty();
		assertThat(resizer.toJpeg(new byte[0])).isEmpty();
		byte[] png = image("png", 600, 900, BufferedImage.TYPE_INT_RGB, Color.RED);
		assertThat(resizer.toJpeg(java.util.Arrays.copyOf(png, 40))).isEmpty();
		assertThat(new CoverResizer(200, 0.8f, 100_000).toJpeg(png)).isEmpty();
	}

	/** An image of one colour with a darker band, encoded in {@code format}. */
	static byte[] image(String format, int width, int height, int type, Color colour) {
		BufferedImage image = new BufferedImage(width, height, type);
		Graphics2D g = image.createGraphics();
		g.setBackground(colour);
		g.clearRect(0, 0, width, height);
		g.setColor(Color.DARK_GRAY);
		g.fillRect(0, 0, width, height / 10);
		g.dispose();
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try {
			assertThat(ImageIO.write(image, format, out)).as(format).isTrue();
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		return out.toByteArray();
	}

	static BufferedImage decode(byte[] bytes) {
		try {
			return ImageIO.read(new ByteArrayInputStream(bytes));
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

}
