package com.chaekcheck.popular;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.imageio.ImageIO;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import com.chaekcheck.popular.internal.PopularCategory;
import com.chaekcheck.popular.internal.fetch.KpipaFixtures;
import com.chaekcheck.popular.internal.fetch.KpipaRequest;
import com.chaekcheck.popular.internal.fetch.KpipaResponse;
import com.chaekcheck.popular.internal.fetch.KpipaTransport;
import com.chaekcheck.popular.internal.fetch.RequestPacer;

/**
 * The site in memory, from the saved answers (plan cycle 10 C3: tests never call the real site), and a ticker that
 * does not sleep. Every list answers the saved "전체" list; each month's cover paths get the month in their name except
 * rank 1's, which every month shares. Sales counts are the 202609 counts (plan research kpipa.md 2-1절). This class is
 * the primary {@link KpipaTransport} of a context that imports it.
 */
@TestConfiguration(proxyBeanMethods = false)
@Primary
public class FakeKpipaSite implements KpipaTransport {

	/** Rank 1's cover in the saved list: the same in every month. */
	public static final String SHARED_COVER = "/files/isbn/2026/07/06/s_a455c2047d9945bc9ca9a7a2cec718c9.jpg";

	static final Map<String, Long> SALES_202609 = Map.ofEntries(Map.entry("00000000000000000303", 883_478L),
			Map.entry("00000000000000000223", 739_250L), Map.entry("00000000000000000449", 731_208L),
			Map.entry("00000000000000000178", 463_935L), Map.entry("00000000000000001621", 406_420L),
			Map.entry("00000000000000000721", 387_669L), Map.entry("00000000000000002344", 360_765L),
			Map.entry("00000000000000000001", 213_287L), Map.entry("00000000000000002169", 152_493L),
			Map.entry("00000000000000002476", 141_480L), Map.entry("00000000000000001443", 130_289L),
			Map.entry("00000000000000001381", 89_508L), Map.entry("00000000000000001991", 61_812L),
			Map.entry("00000000000000000876", 21_547L), Map.entry("00000000000000000408", 11_448L),
			Map.entry("00000000000000001782", 7_908L));

	/** A cover as the site sends them: 458 x 687, wider than stored. */
	static final byte[] JPEG = image("jpeg", BufferedImage.TYPE_INT_RGB);

	/** A PNG cover with transparency. */
	static final byte[] PNG = image("png", BufferedImage.TYPE_INT_ARGB);

	private static final Pattern FORM_VALUE = Pattern.compile("(?:^|&)(txtDate|txtFromDate|searchCtg)=([^&]*)");

	private final List<KpipaRequest> requests = new CopyOnWriteArrayList<>();

	/** Site category ids whose list answers 49 books. */
	private final Set<String> shortLists = ConcurrentHashMap.newKeySet();

	/** Cover paths that answer 404. */
	private final Set<String> missingCovers = ConcurrentHashMap.newKeySet();

	/** Cover paths that answer {@code image/jpeg} bytes no image reader can read. */
	private final Set<String> brokenCovers = ConcurrentHashMap.newKeySet();

	/** Cover paths whose request is interrupted, as when the server stops. */
	private final Set<String> interruptedCovers = ConcurrentHashMap.newKeySet();

	@Bean
	@Primary
	RequestPacer.Ticker fakeKpipaTicker() {
		return new KpipaFixtures.FakeTicker();
	}

	public void reset() {
		requests.clear();
		shortLists.clear();
		missingCovers.clear();
		brokenCovers.clear();
		interruptedCovers.clear();
	}

	public List<KpipaRequest> requests() {
		return List.copyOf(requests);
	}

	public void shortList(PopularCategory category) {
		shortLists.add(category.siteId());
	}

	public void missingCover(String path) {
		missingCovers.add(path);
	}

	public void brokenCover(String path) {
		brokenCovers.add(path);
	}

	public void interruptAtCover(String path) {
		interruptedCovers.add(path);
	}

	/** The path a saved cover path (not rank 1's) has in {@code yyyymm}'s lists. */
	public static String coverOf(String yyyymm, String savedPath) {
		return savedPath.replace("/s_", "/s_" + yyyymm + "_");
	}

	@Override
	public Connection open() {
		return new Connection() {

			@Override
			public KpipaResponse send(KpipaRequest request) throws IOException, InterruptedException {
				requests.add(request);
				return answer(request);
			}

			@Override
			public void close() {
			}

		};
	}

	private KpipaResponse answer(KpipaRequest request) throws InterruptedException {
		String path = request.path();
		if (path.equals("/home/v3/addition/adiStatsCtgSaleRank") && request.method().equals("GET")) {
			return html(KpipaFixtures.html("rank-page.html"));
		}
		Map<String, String> form = form(request.form());
		if (path.equals("/home/v3/addition/adiStatsCtgSaleRank/search")) {
			if (shortLists.contains(form.get("searchCtg"))) {
				return html(KpipaFixtures.html("rank-search-49-books.html"));
			}
			String month = form.get("txtDate");
			String list = KpipaFixtures.html("rank-search-all.html").replace("/s_", "/s_" + month + "_");
			return html(list.replace(coverOf(month, SHARED_COVER), SHARED_COVER));
		}
		if (path.equals("/home/v3/addition/adiStatsCtgSaleCnt/search")) {
			long count = SALES_202609.get(form.get("searchCtg"));
			return html(KpipaFixtures.html("sales-count-novel.html")
				.replace("dataX1 = Number(\"883478\")", "dataX1 = Number(\"" + count + "\")"));
		}
		if (path.startsWith("/files/")) {
			if (interruptedCovers.contains(path)) {
				throw new InterruptedException("server stopping");
			}
			if (missingCovers.contains(path)) {
				return new KpipaResponse(404, "text/html", new byte[0]);
			}
			if (brokenCovers.contains(path)) {
				return new KpipaResponse(200, "image/jpeg", ("JPEG " + path).getBytes(StandardCharsets.UTF_8));
			}
			return path.endsWith(".png") ? new KpipaResponse(200, "image/png", PNG)
					: new KpipaResponse(200, "image/jpeg", JPEG);
		}
		return new KpipaResponse(404, "text/html", new byte[0]);
	}

	private static byte[] image(String format, int type) {
		BufferedImage image = new BufferedImage(458, 687, type);
		Graphics2D g = image.createGraphics();
		g.setColor(Color.ORANGE);
		g.fillRect(0, 0, 458, 300);
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

	private static Map<String, String> form(String body) {
		Map<String, String> values = new ConcurrentHashMap<>();
		if (body != null) {
			Matcher matcher = FORM_VALUE.matcher(body);
			while (matcher.find()) {
				values.put(matcher.group(1), matcher.group(2));
			}
		}
		return values;
	}

	private static KpipaResponse html(String text) {
		return new KpipaResponse(200, "text/html;charset=UTF-8", text.getBytes(StandardCharsets.UTF_8));
	}

}
