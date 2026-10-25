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
 * with keywords. A {@link CircuitBreaker} stops asking a failing encoder for a while (one log line when it opens and
 * when it closes, with the searches that went without vectors meanwhile).
 */
@Component
class KureQuestionVectors implements QuestionVectors, AutoCloseable {

	private static final Logger log = LoggerFactory.getLogger(KureQuestionVectors.class);

	private final QuestionEncoder encoder;

	private final BookVectorFile books;

	private final ExecutorService pool;

	private final CircuitBreaker breaker;

	private final long openForSeconds;

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
		this.breaker = new CircuitBreaker(Math.max(1, settings.failuresToOpen()), settings.openFor(), System::nanoTime);
		this.openForSeconds = settings.openFor().toSeconds();
	}

	@Override
	public boolean on() {
		return encoder != null;
	}

	@Override
	public CompletableFuture<List<Long>> nearest(String question, int limit) {
		if (!on() || !breaker.allow()) {
			return CompletableFuture.completedFuture(List.of());
		}
		return encoded(question).thenApply(vector -> books.nearest(vector, limit));
	}

	@Override
	public CompletableFuture<List<float[]>> vectors(List<String> texts) {
		if (!on() || !breaker.allow()) {
			return CompletableFuture.failedFuture(new Unavailable());
		}
		List<CompletableFuture<float[]>> runs = texts.stream()
			.map(text -> CompletableFuture.supplyAsync(() -> encoder.encode(text), pool))
			.toList();
		CompletableFuture<List<float[]>> all = CompletableFuture.allOf(runs.toArray(CompletableFuture[]::new))
			.thenApply(done -> runs.stream().map(CompletableFuture::join).toList());
		CompletableFuture<List<float[]>> result = counted(all);
		// a caller that gives up (cancels) stops the runs not started yet
		result.whenComplete((found, error) -> {
			if (error instanceof java.util.concurrent.CancellationException) {
				runs.forEach(run -> run.cancel(true));
			}
		});
		return result;
	}

	@Override
	public List<Long> nearest(float[] vector, int limit) {
		return on() ? books.nearest(vector, limit) : List.of();
	}

	private CompletableFuture<float[]> encoded(String text) {
		return counted(CompletableFuture.supplyAsync(() -> encoder.encode(text), pool));
	}

	/**
	 * One lookup's result for the circuit breaker; runs given up as too slow count as a failure (and a half-open trial
	 * always ends one way or the other).
	 */
	private <T> CompletableFuture<T> counted(CompletableFuture<T> lookup) {
		return lookup.whenComplete((found, error) -> {
			if (error == null) {
				if (breaker.success() == CircuitBreaker.Change.CLOSED) {
					log.info("ai search vectors: the encoder answers again; {} search(es) went without vectors",
							breaker.takeSkipped());
				}
			}
			else if (breaker.failure() == CircuitBreaker.Change.OPENED) {
				Throwable cause = error.getCause() == null ? error : error.getCause();
				log.warn("ai search vectors: the encoder failed ({}), not asked for {} s", cause.getMessage(),
						openForSeconds);
			}
		});
	}

	/** The breaker's state (tests). */
	CircuitBreaker.State circuit() {
		return breaker.state();
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
