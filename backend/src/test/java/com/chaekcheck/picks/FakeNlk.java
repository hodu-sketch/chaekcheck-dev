package com.chaekcheck.picks;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.imageio.ImageIO;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import com.chaekcheck.picks.internal.fetch.NlkFixtures;
import com.chaekcheck.picks.internal.fetch.NlkResponse;
import com.chaekcheck.picks.internal.fetch.NlkTransport;
import com.chaekcheck.picks.internal.fetch.RequestPacer;

/**
 * The National Library of Korea in memory (plan cycle 14 C3: tests never call the real API or covers), and a ticker
 * that does not sleep. The list answers the saved two-year answer unless a test sets another; every cover answers a
 * 458 x 687 JPEG unless set otherwise. This class is the primary {@link NlkTransport} of a context that imports it.
 */
@TestConfiguration(proxyBeanMethods = false)
@Primary
public class FakeNlk implements NlkTransport {

	/** A cover as the library sends them: 458 x 687, wider than stored. */
	public static final byte[] JPEG = image("jpeg", BufferedImage.TYPE_INT_RGB, 458, 687);

	public static final byte[] PNG = image("png", BufferedImage.TYPE_INT_ARGB, 458, 687);

	public static final byte[] BMP = image("bmp", BufferedImage.TYPE_INT_RGB, 300, 450);

	private final List<URI> requests = new CopyOnWriteArrayList<>();

	private final Map<String, NlkResponse> covers = new ConcurrentHashMap<>();

	private final NlkFixtures.FakeTicker ticker = new NlkFixtures.FakeTicker();

	private volatile NlkResponse list;

	private volatile IOException listFailure;

	/** A cover whose request is interrupted, as when the server stops. */
	private volatile String interruptAt;

	public FakeNlk() {
		reset();
	}

	@Bean
	@Primary
	RequestPacer.Ticker fakeNlkTicker() {
		return ticker;
	}

	public void reset() {
		requests.clear();
		covers.clear();
		ticker.clear();
		list = xml(NlkFixtures.saseo2y());
		listFailure = null;
		interruptAt = null;
	}

	public List<URI> requests() {
		return List.copyOf(requests);
	}

	public NlkFixtures.FakeTicker ticker() {
		return ticker;
	}

	public void list(NlkResponse response) {
		this.list = response;
	}

	public void listXml(String xml) {
		this.list = xml(xml);
	}

	public void listFails(IOException failure) {
		this.listFailure = failure;
	}

	public void interruptAt(String url) {
		this.interruptAt = url;
	}

	/** What the cover at {@code url} answers. */
	public void cover(String url, NlkResponse response) {
		covers.put(url, response);
	}

	public static NlkResponse xml(String xml) {
		return new NlkResponse(200, "text/xml;charset=UTF-8", xml.getBytes(StandardCharsets.UTF_8));
	}

	@Override
	public NlkResponse get(URI uri, long maxBodyBytes) throws IOException, InterruptedException {
		requests.add(uri);
		// every request takes 100ms
		ticker.advance(java.time.Duration.ofMillis(100));
		if (uri.getPath().equals("/NL/search/openApi/saseoApi.do")) {
			if (listFailure != null) {
				throw listFailure;
			}
			return limited(list, maxBodyBytes);
		}
		if (uri.toString().equals(interruptAt)) {
			throw new InterruptedException("server stopping");
		}
		NlkResponse cover = covers.getOrDefault(uri.toString(), new NlkResponse(200, "image/jpeg", JPEG));
		return limited(cover, maxBodyBytes);
	}

	private static NlkResponse limited(NlkResponse response, long maxBodyBytes) throws BodyTooLargeException {
		if (response.body() != null && response.body().length > maxBodyBytes) {
			throw new BodyTooLargeException(maxBodyBytes);
		}
		return response;
	}

	public static byte[] image(String format, int type, int width, int height) {
		BufferedImage image = new BufferedImage(width, height, type);
		Graphics2D g = image.createGraphics();
		g.setColor(Color.ORANGE);
		g.fillRect(0, 0, width, height / 2);
		g.dispose();
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try {
			ImageIO.write(image, format, out);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		return out.toByteArray();
	}

}
