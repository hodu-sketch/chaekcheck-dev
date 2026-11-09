package com.chaekcheck.collection.internal.importer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Cycle 29 (ADR-0174): after an import changed {@code catalog.book_search} or the catalog, asks each running backend
 * to read the AI search's books again ({@code POST /internal/aisearch/books/reload}). The URLs are
 * {@code chaekcheck.collection.import.reload-urls}; in production both slots ({@code http://backend-blue:8080/...},
 * {@code http://backend-green:8080/...}, docs/maintenance/6-aisearch.md 3). A slot that is not running or does not
 * answer is logged and skipped: the import is done either way, and a backend reads the books when it starts.
 */
final class SearchBooksReloadRequest implements Runnable {

	private static final Logger log = LoggerFactory.getLogger(SearchBooksReloadRequest.class);

	/** How long one slot gets to answer [안]. */
	static final Duration TIMEOUT = Duration.ofSeconds(5);

	private final List<URI> urls;

	private final HttpClient client;

	SearchBooksReloadRequest(List<URI> urls) {
		this.urls = urls == null ? List.of() : List.copyOf(urls);
		this.client = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
	}

	@Override
	public void run() {
		if (urls.isEmpty()) {
			log.info("ai search books reload: no reload-urls set; the backends read the books when they next start");
			return;
		}
		for (URI url : urls) {
			try {
				HttpResponse<Void> response = client.send(
						HttpRequest.newBuilder(url).timeout(TIMEOUT).POST(HttpRequest.BodyPublishers.noBody()).build(),
						HttpResponse.BodyHandlers.discarding());
				log.info("ai search books reload: {} answered {}", url, response.statusCode());
			}
			catch (java.io.IOException e) {
				log.warn("ai search books reload: {} not reached ({})", url, e.getClass().getName());
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				log.warn("ai search books reload: interrupted at {}", url);
				return;
			}
		}
	}

}
