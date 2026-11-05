package com.chaekcheck.aisearch.internal.flow;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.chaekcheck.aisearch.internal.vector.QuestionVectors;

/**
 * What a follow-up means, told by the question vector (user 2026-10-29 「각각 테스트해보고」: on 230 held-out rude
 * sentences the vector beat the rules alone, and the two mixed beat both, docs/features/aisearch/reports/2026-10-29-follow-ups.md):
 * the reply is compared with example replies ({@code aisearch/intent-examples.tsv}) and takes the label of its
 * {@value #K} nearest, weighted by closeness. The examples are encoded once, on first use, by the same encoder as the
 * questions; until then, or with the vectors off, every answer is null and the rules decide alone.
 */
final class IntentVectors {

	private static final Logger log = LoggerFactory.getLogger(IntentVectors.class);

	static final String RECENCY = "recency";

	static final String ALL_CHOICES = "all_choices";

	/** How many nearest examples vote (as in scripts/aisearch/eval/intent_compare.py). */
	static final int K = 5;

	/** Examples encoded at a time, so one lookup does not hold the encoder long. */
	private static final int CHUNK = 16;

	/** How long a failed encoding of the examples waits before it is tried again. */
	private static final Duration RETRY = Duration.ofMinutes(1);

	private record Example(String label, String text) {
	}

	private record Encoded(String label, float[] vector) {
	}

	private final QuestionVectors vectors;

	private final Map<String, List<Example>> examples;

	private volatile Map<String, List<Encoded>> encoded;

	private volatile CompletableFuture<?> encoding;

	private volatile long failedAt;

	IntentVectors(QuestionVectors vectors) {
		this(vectors, read());
	}

	IntentVectors(QuestionVectors vectors, Map<String, List<String[]>> rows) {
		this.vectors = vectors;
		Map<String, List<Example>> sets = new HashMap<>();
		rows.forEach((set, list) -> sets.put(set, list.stream().map(r -> new Example(r[0], r[1])).toList()));
		this.examples = Map.copyOf(sets);
	}

	/**
	 * The label of the {@value #K} examples of the set nearest the vector, or null when the examples are not encoded
	 * (yet) or the vector is null.
	 */
	String label(String set, float[] vector) {
		Map<String, List<Encoded>> ready = ready();
		if (ready == null || vector == null || !ready.containsKey(set)) {
			return null;
		}
		List<Encoded> list = ready.get(set);
		List<Encoded> nearest = new ArrayList<>(list);
		nearest.sort((a, b) -> Float.compare(dot(b.vector(), vector), dot(a.vector(), vector)));
		Map<String, Float> votes = new HashMap<>();
		for (Encoded example : nearest.subList(0, Math.min(K, nearest.size()))) {
			votes.merge(example.label(), dot(example.vector(), vector), Float::sum);
		}
		return votes.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
	}

	/** The texts' vectors in order, waiting at most {@code wait}; null when the vectors are off, late or failed. */
	List<float[]> encode(List<String> texts, Duration wait) {
		if (texts.isEmpty() || !vectors.on()) {
			return null;
		}
		CompletableFuture<List<float[]>> lookup = vectors.vectors(texts);
		try {
			return lookup.get(wait.toMillis(), TimeUnit.MILLISECONDS);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return null;
		}
		catch (Exception e) {
			lookup.cancel(true);
			return null;
		}
	}

	static float dot(float[] a, float[] b) {
		float sum = 0;
		for (int i = 0; i < a.length && i < b.length; i++) {
			sum += a[i] * b[i];
		}
		return sum;
	}

	/** The encoded examples, or null while they are not; the first call starts the encoding beside the search. */
	private Map<String, List<Encoded>> ready() {
		Map<String, List<Encoded>> done = encoded;
		if (done != null || !vectors.on()) {
			return done;
		}
		synchronized (this) {
			if (encoded == null && (encoding == null || encoding.isDone())
					&& System.currentTimeMillis() - failedAt > RETRY.toMillis()) {
				encoding = CompletableFuture.runAsync(this::encodeAll);
			}
		}
		return null;
	}

	private void encodeAll() {
		Map<String, List<Encoded>> out = new HashMap<>();
		try {
			for (Map.Entry<String, List<Example>> set : examples.entrySet()) {
				List<Encoded> list = new ArrayList<>();
				List<Example> all = set.getValue();
				for (int from = 0; from < all.size(); from += CHUNK) {
					List<Example> chunk = all.subList(from, Math.min(all.size(), from + CHUNK));
					List<float[]> found = vectors.vectors(chunk.stream().map(Example::text).toList())
						.get(30, TimeUnit.SECONDS);
					for (int i = 0; i < chunk.size(); i++) {
						list.add(new Encoded(chunk.get(i).label(), found.get(i)));
					}
				}
				out.put(set.getKey(), List.copyOf(list));
			}
			encoded = Map.copyOf(out);
			log.info("ai search: intent examples encoded ({})", examples.values().stream().mapToInt(List::size).sum());
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			failedAt = System.currentTimeMillis();
		}
		catch (Exception e) {
			failedAt = System.currentTimeMillis();
			log.info("ai search: intent examples not encoded ({}), the rules decide", e.getClass().getSimpleName());
		}
	}

	/** {@code aisearch/intent-examples.tsv}: set, label and text a line; # lines are notes. */
	static Map<String, List<String[]>> read() {
		Map<String, List<String[]>> rows = new HashMap<>();
		try (InputStream in = IntentVectors.class.getResourceAsStream("/aisearch/intent-examples.tsv")) {
			if (in == null) {
				return rows;
			}
			BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
			for (String line = reader.readLine(); line != null; line = reader.readLine()) {
				if (line.isBlank() || line.startsWith("#")) {
					continue;
				}
				String[] parts = line.split("\t", 3);
				if (parts.length == 3) {
					rows.computeIfAbsent(parts[0], k -> new ArrayList<>()).add(new String[] { parts[1], parts[2] });
				}
			}
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		return rows;
	}

}
