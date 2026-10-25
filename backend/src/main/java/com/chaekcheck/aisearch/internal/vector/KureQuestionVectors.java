package com.chaekcheck.aisearch.internal.vector;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import tools.jackson.databind.json.JsonMapper;

import com.chaekcheck.aisearch.internal.config.AiSearchProperties;

/**
 * {@link QuestionVectors} over KURE-v1 (user 2026-10-25): the book vectors ({@link BookVectorFile}) live in this
 * process; the question becomes a vector where {@code chaekcheck.aisearch.vector.encoder} says, in this process
 * ({@link OnnxQuestionEncoder}) or on the embedding server ({@link RemoteQuestionEncoder}). The work runs off the
 * searches' own threads: a small pool for the model (a run holds its thread in native code), virtual threads for the
 * remote requests. Any file missing or failing to load leaves the vectors off with one warning; the search goes on
 * with keywords.
 */
@Component
class KureQuestionVectors implements QuestionVectors, AutoCloseable {

	private static final Logger log = LoggerFactory.getLogger(KureQuestionVectors.class);

	private final QuestionEncoder encoder;

	private final BookVectorFile books;

	private final ExecutorService pool;

	KureQuestionVectors(AiSearchProperties properties, JsonMapper json) {
		AiSearchProperties.Vector settings = properties.vector();
		QuestionEncoder encoder = null;
		BookVectorFile books = null;
		ExecutorService pool = null;
		if (settings.enabled()) {
			try {
				Path dir = Path.of(settings.modelDir());
				if (!Files.isRegularFile(dir.resolve(BookVectorFile.NAME))) {
					throw new IllegalStateException("missing " + BookVectorFile.NAME + " in " + dir);
				}
				long started = System.nanoTime();
				books = BookVectorFile.read(dir.resolve(BookVectorFile.NAME));
				if (settings.encoder() == AiSearchProperties.Encoder.LOCAL) {
					encoder = new OnnxQuestionEncoder(dir, settings.threads());
					pool = Executors.newFixedThreadPool(2,
							Thread.ofPlatform().name("ai-search-vector-", 0).daemon().factory());
				}
				else {
					encoder = new RemoteQuestionEncoder(settings.remote(), json);
					pool = Executors.newVirtualThreadPerTaskExecutor();
				}
				log.info("ai search vectors on ({}): {} books x {}, loaded in {} ms", settings.encoder(), books.count(),
						books.dim(), (System.nanoTime() - started) / 1_000_000);
			}
			catch (Exception | UnsatisfiedLinkError e) {
				log.warn("ai search vectors off: {}", e.getMessage() == null ? e.getClass().getName() : e.getMessage());
				if (encoder != null) {
					encoder.close();
				}
				encoder = null;
				books = null;
				if (pool != null) {
					pool.shutdownNow();
					pool = null;
				}
			}
		}
		this.encoder = encoder;
		this.books = books;
		this.pool = pool;
	}

	@Override
	public boolean on() {
		return encoder != null;
	}

	@Override
	public CompletableFuture<List<Long>> nearest(String question, int limit) {
		if (!on()) {
			return CompletableFuture.completedFuture(List.of());
		}
		return CompletableFuture.supplyAsync(() -> books.nearest(encoder.encode(question), limit), pool);
	}

	/** The question's normalised vector (tests). */
	float[] encode(String question) {
		return encoder.encode(question);
	}

	@Override
	public void close() {
		if (pool != null) {
			pool.shutdownNow();
		}
		if (encoder != null) {
			encoder.close();
		}
	}

}
