package com.chaekcheck.aisearch.internal.web;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.concurrent.atomic.AtomicBoolean;

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import com.chaekcheck.aisearch.internal.config.AiSearchProperties;
import com.chaekcheck.aisearch.internal.match.SearchBooks;

/**
 * Cycle 29 (ADR-0174): when the AI search's books are read. Once when the application is ready (AI search on), then on
 * {@code POST /internal/aisearch/books/reload}, which the {@code book-texts} and catalog imports send to every backend
 * slot when they end. The read runs in the background and the answer is {@code 202} at once; a request during a read
 * makes one more read after it, so a table swapped in during the read is not missed.
 * <p>
 * Not for outside callers: nginx passes only {@code /api/} to the backend (deploy/nginx/chaekcheck-locations.conf), the
 * backend port is not published, and a request from an address that is not loopback or private gets {@code 404}.
 */
@RestController
class SearchBooksReload {

	private static final Logger log = LoggerFactory.getLogger(SearchBooksReload.class);

	private final SearchBooks books;

	private final AiSearchProperties properties;

	private final AtomicBoolean running = new AtomicBoolean();

	private final AtomicBoolean again = new AtomicBoolean();

	SearchBooksReload(SearchBooks books, AiSearchProperties properties) {
		this.books = books;
		this.properties = properties;
	}

	@EventListener(ApplicationReadyEvent.class)
	void readAtStart() {
		if (properties.enabled()) {
			start();
		}
	}

	@PostMapping("/internal/aisearch/books/reload")
	ResponseEntity<Void> reload(HttpServletRequest request) {
		if (!inside(request.getRemoteAddr())) {
			return ResponseEntity.notFound().build();
		}
		log.info("ai search books reload asked");
		start();
		return ResponseEntity.accepted().build();
	}

	/** Starts a background read, or marks one more read when one is running. */
	void start() {
		again.set(true);
		if (!running.compareAndSet(false, true)) {
			return;
		}
		Thread.ofVirtual().name("ai-search-books-reload").start(() -> {
			try {
				while (again.getAndSet(false)) {
					try {
						books.reload();
					}
					catch (RuntimeException e) {
						// logged by SearchBooks; the books in use stay
					}
				}
			}
			finally {
				running.set(false);
			}
			if (again.get()) {
				start();
			}
		});
	}

	static boolean inside(String address) {
		try {
			InetAddress from = InetAddress.getByName(address);
			return from.isLoopbackAddress() || from.isSiteLocalAddress();
		}
		catch (UnknownHostException | RuntimeException e) {
			return false;
		}
	}

}
