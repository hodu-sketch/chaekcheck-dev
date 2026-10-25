package com.chaekcheck.embed;

import java.nio.file.Path;
import java.util.concurrent.Semaphore;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * {@link Embedder} over {@link QueryEncoder}, loaded at start. At most {@code embed.max-concurrent} runs at once (the
 * others wait); a model that fails to load leaves the service up but not ready (503), with one warning.
 */
@Component
class OnnxEmbedder implements Embedder, AutoCloseable {

	private static final Logger log = LoggerFactory.getLogger(OnnxEmbedder.class);

	private final QueryEncoder encoder;

	private final Semaphore runs;

	OnnxEmbedder(EmbedProperties properties) {
		QueryEncoder encoder = null;
		try {
			long started = System.nanoTime();
			encoder = new QueryEncoder(Path.of(properties.modelDir()), properties.threads());
			log.info("embed model loaded in {} ms", (System.nanoTime() - started) / 1_000_000);
		}
		catch (Exception | UnsatisfiedLinkError e) {
			log.warn("embed model not loaded: {}", e.getMessage() == null ? e.getClass().getName() : e.getMessage());
		}
		this.encoder = encoder;
		this.runs = new Semaphore(Math.max(1, properties.maxConcurrent()));
	}

	@Override
	public boolean ready() {
		return encoder != null;
	}

	@Override
	public float[] embed(String text) {
		runs.acquireUninterruptibly();
		try {
			return encoder.encode(text);
		}
		finally {
			runs.release();
		}
	}

	@Override
	public void close() {
		if (encoder != null) {
			encoder.close();
		}
	}

}
