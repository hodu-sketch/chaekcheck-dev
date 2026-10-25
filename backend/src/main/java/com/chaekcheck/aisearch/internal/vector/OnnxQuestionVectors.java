package com.chaekcheck.aisearch.internal.vector;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;

import com.chaekcheck.aisearch.internal.config.AiSearchProperties;

/**
 * {@link QuestionVectors} with the int8 KURE-v1 query model in this process (user 2026-10-25): ONNX Runtime for the
 * model ({@value #MODEL}, its weights in the {@code .data} file beside it, read by mmap), the DJL Hugging Face
 * tokenizer for {@value #TOKENIZER} (the original tokenizer cut to the model's 88,446 pieces), and
 * {@link BookVectorFile} for the books. On the server (2026-10-25, two threads): about 450 MB, 106 ms a question,
 * vectors 0.988 alike to the float32 model's. Everything runs on a small pool of its own, never on the searches'
 * virtual threads (a model run holds its thread in native code). Any file missing or failing to load leaves the
 * vectors off with one warning; the search goes on with keywords.
 */
@Component
class OnnxQuestionVectors implements QuestionVectors, AutoCloseable {

	static final String MODEL = "kure-query-int8-ext.onnx";

	static final String TOKENIZER = "tokenizer-cut.json";

	/** Longest question in pieces, far over the 200 characters a question may have. */
	static final int MAX_PIECES = 256;

	private static final Logger log = LoggerFactory.getLogger(OnnxQuestionVectors.class);

	private final OrtEnvironment env;

	private final OrtSession session;

	private final HuggingFaceTokenizer tokenizer;

	private final BookVectorFile books;

	private final ExecutorService pool;

	OnnxQuestionVectors(AiSearchProperties properties) {
		AiSearchProperties.Vector settings = properties.vector();
		OrtEnvironment env = null;
		OrtSession session = null;
		HuggingFaceTokenizer tokenizer = null;
		BookVectorFile books = null;
		ExecutorService pool = null;
		if (settings.enabled()) {
			try {
				Path dir = Path.of(settings.modelDir());
				for (String name : List.of(MODEL, TOKENIZER, BookVectorFile.NAME)) {
					if (!Files.isRegularFile(dir.resolve(name))) {
						throw new IllegalStateException("missing " + name + " in " + dir);
					}
				}
				long started = System.nanoTime();
				// the tokenizer's native library is in the jar; nothing is fetched from the network
				System.setProperty("ai.djl.offline", "true");
				tokenizer = HuggingFaceTokenizer.newInstance(dir.resolve(TOKENIZER),
						Map.of("addSpecialTokens", "true", "truncation", "true", "maxLength",
								String.valueOf(MAX_PIECES), "padding", "false"));
				env = OrtEnvironment.getEnvironment();
				OrtSession.SessionOptions options = new OrtSession.SessionOptions();
				options.setIntraOpNumThreads(Math.max(1, settings.threads()));
				options.setInterOpNumThreads(1);
				session = env.createSession(dir.resolve(MODEL).toString(), options);
				books = BookVectorFile.read(dir.resolve(BookVectorFile.NAME));
				pool = Executors.newFixedThreadPool(2, Thread.ofPlatform().name("ai-search-vector-", 0).daemon().factory());
				log.info("ai search vectors on: {} books x {}, loaded in {} ms", books.count(), books.dim(),
						(System.nanoTime() - started) / 1_000_000);
			}
			catch (Exception | UnsatisfiedLinkError e) {
				log.warn("ai search vectors off: {}", e.getMessage() == null ? e.getClass().getName() : e.getMessage());
				closeQuietly(session, tokenizer);
				session = null;
				tokenizer = null;
				books = null;
				if (pool != null) {
					pool.shutdownNow();
					pool = null;
				}
			}
		}
		this.env = env;
		this.session = session;
		this.tokenizer = tokenizer;
		this.books = books;
		this.pool = pool;
	}

	@Override
	public boolean on() {
		return session != null;
	}

	@Override
	public CompletableFuture<List<Long>> nearest(String question, int limit) {
		if (!on()) {
			return CompletableFuture.completedFuture(List.of());
		}
		return CompletableFuture.supplyAsync(() -> books.nearest(encode(question), limit), pool);
	}

	/** The question's normalised vector. */
	float[] encode(String question) {
		Encoding encoding = tokenizer.encode(question);
		long[] ids = encoding.getIds();
		long[] mask = encoding.getAttentionMask();
		try (OnnxTensor idTensor = OnnxTensor.createTensor(env, new long[][] { ids });
				OnnxTensor maskTensor = OnnxTensor.createTensor(env, new long[][] { mask });
				OrtSession.Result result = session.run(Map.of("input_ids", idTensor, "attention_mask", maskTensor))) {
			return ((float[][]) result.get(0).getValue())[0];
		}
		catch (OrtException e) {
			throw new IllegalStateException("the question model failed", e);
		}
	}

	@Override
	public void close() {
		if (pool != null) {
			pool.shutdownNow();
		}
		closeQuietly(session, tokenizer);
	}

	private static void closeQuietly(OrtSession session, HuggingFaceTokenizer tokenizer) {
		try {
			if (session != null) {
				session.close();
			}
		}
		catch (OrtException e) {
			// closing at the end only
		}
		if (tokenizer != null) {
			tokenizer.close();
		}
	}

}
