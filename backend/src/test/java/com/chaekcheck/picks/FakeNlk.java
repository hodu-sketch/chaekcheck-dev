package com.chaekcheck.picks;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import com.chaekcheck.picks.internal.fetch.NlkFixtures;
import com.chaekcheck.picks.internal.fetch.NlkResponse;
import com.chaekcheck.picks.internal.fetch.NlkTransport;
import com.chaekcheck.picks.internal.fetch.RequestPacer;

/**
 * The National Library of Korea in memory (plan cycle 14 C3: tests never call the real API or covers), and a ticker
 * that does not sleep. The list answers the saved two-year answer unless a test sets another; any other address answers
 * 404 (no covers are fetched since cycle 26 deploy 2, ADR-0166). This class is the primary {@link NlkTransport} of a
 * context that imports it.
 */
@TestConfiguration(proxyBeanMethods = false)
@Primary
public class FakeNlk implements NlkTransport {

	private final List<URI> requests = new CopyOnWriteArrayList<>();

	private final NlkFixtures.FakeTicker ticker = new NlkFixtures.FakeTicker();

	private volatile NlkResponse list;

	private volatile IOException listFailure;

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
		ticker.clear();
		list = xml(NlkFixtures.saseo2y());
		listFailure = null;
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
		return new NlkResponse(404, "text/html", new byte[0]);
	}

	private static NlkResponse limited(NlkResponse response, long maxBodyBytes) throws BodyTooLargeException {
		if (response.body() != null && response.body().length > maxBodyBytes) {
			throw new BodyTooLargeException(maxBodyBytes);
		}
		return response;
	}

}
