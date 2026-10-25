package com.chaekcheck.aisearch.internal.vector;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;

/**
 * {@link QuestionEncoder} with the int8 KURE-v1 query model in this process: ONNX Runtime for {@value #MODEL} (its
 * weights in the {@code .data} file beside it, read by mmap) and the DJL Hugging Face tokenizer for
 * {@value #TOKENIZER} (the original tokenizer cut to the model's 88,446 pieces). On the server (2026-10-25, two
 * threads): about 450 MB, 106 ms a question, vectors 0.988 alike to the float32 model's. {@code embed-service/} holds
 * the same code ({@code com.chaekcheck.embed.QueryEncoder}); keep the two alike.
 */
final class OnnxQuestionEncoder implements QuestionEncoder {

	static final String MODEL = "kure-query-int8-ext.onnx";

	static final String TOKENIZER = "tokenizer-cut.json";

	/** Longest question in pieces, far over the 200 characters a question may have. */
	static final int MAX_PIECES = 256;

	private final OrtEnvironment env;

	private final OrtSession session;

	private final HuggingFaceTokenizer tokenizer;

	OnnxQuestionEncoder(Path dir, int threads) throws Exception {
		for (String name : List.of(MODEL, TOKENIZER)) {
			if (!Files.isRegularFile(dir.resolve(name))) {
				throw new IllegalStateException("missing " + name + " in " + dir);
			}
		}
		// the tokenizer's native library is in the jar; nothing is fetched from the network
		System.setProperty("ai.djl.offline", "true");
		HuggingFaceTokenizer tokenizer = HuggingFaceTokenizer.newInstance(dir.resolve(TOKENIZER), Map.of(
				"addSpecialTokens", "true", "truncation", "true", "maxLength", String.valueOf(MAX_PIECES), "padding",
				"false"));
		try {
			env = OrtEnvironment.getEnvironment();
			OrtSession.SessionOptions options = new OrtSession.SessionOptions();
			options.setIntraOpNumThreads(Math.max(1, threads));
			options.setInterOpNumThreads(1);
			session = env.createSession(dir.resolve(MODEL).toString(), options);
		}
		catch (Exception | UnsatisfiedLinkError e) {
			tokenizer.close();
			throw e;
		}
		this.tokenizer = tokenizer;
	}

	@Override
	public float[] encode(String question) {
		Encoding encoding = tokenizer.encode(question);
		try (OnnxTensor idTensor = OnnxTensor.createTensor(env, new long[][] { encoding.getIds() });
				OnnxTensor maskTensor = OnnxTensor.createTensor(env, new long[][] { encoding.getAttentionMask() });
				OrtSession.Result result = session.run(Map.of("input_ids", idTensor, "attention_mask", maskTensor))) {
			return ((float[][]) result.get(0).getValue())[0];
		}
		catch (OrtException e) {
			throw new IllegalStateException("the question model failed", e);
		}
	}

	@Override
	public void close() {
		try {
			session.close();
		}
		catch (OrtException e) {
			// closing at the end only
		}
		tokenizer.close();
	}

}
