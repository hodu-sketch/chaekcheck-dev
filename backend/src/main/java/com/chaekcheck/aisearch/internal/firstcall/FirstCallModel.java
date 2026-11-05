package com.chaekcheck.aisearch.internal.firstcall;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The small networks of the ML first call (user 2026-10-26, docs/features/aisearch/reports/2026-10-26-ml-first-call-decisions.md):
 * every head takes the question's normalised KURE-v1 vector, one hidden layer with ReLU, then its outputs. The file
 * {@code first-call-model.bin} that {@code scripts/aisearch/eval/export_first_call.py} writes, little-endian:
 * {@code "CCFC1\n"}, the JSON header's length (int32), the UTF-8 JSON header (dim, hidden, heads with name, kind,
 * labels, outputs and tau or threshold), then per head W1 [hidden x dim], b1 [hidden], W2 [outputs x hidden], b2
 * [outputs] as float32. Kinds: {@code route} and {@code single} end in softmax, {@code multi} in a sigmoid per label.
 */
final class FirstCallModel {

	static final String NAME = "first-call-model.bin";

	private static final byte[] MAGIC = "CCFC1\n".getBytes(StandardCharsets.US_ASCII);

	enum Kind {

		ROUTE, SINGLE, MULTI

	}

	/**
	 * @param tau the route head's least probability for a way other than the last ({@code book})
	 * @param threshold the multi head's least probability for a label
	 * @param max the multi head's most labels
	 */
	record Head(String name, Kind kind, List<String> labels, double tau, double threshold, int max, float[] w1,
			float[] b1, float[] w2, float[] b2) {
	}

	private final int dim;

	private final int hidden;

	private final Map<String, Head> heads;

	FirstCallModel(int dim, int hidden, List<Head> heads) {
		this.dim = dim;
		this.hidden = hidden;
		Map<String, Head> byName = new LinkedHashMap<>();
		for (Head head : heads) {
			if (head.w1().length != hidden * dim || head.b1().length != hidden
					|| head.w2().length != head.labels().size() * hidden || head.b2().length != head.labels().size()) {
				throw new IllegalArgumentException("the sizes of head " + head.name() + " do not agree");
			}
			byName.put(head.name(), head);
		}
		this.heads = Map.copyOf(byName);
	}

	static FirstCallModel read(Path file, JsonMapper json) throws IOException {
		try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
			ByteBuffer start = ByteBuffer.allocate(MAGIC.length + 4).order(ByteOrder.LITTLE_ENDIAN);
			readFully(channel, start);
			byte[] magic = new byte[MAGIC.length];
			start.get(magic);
			if (!Arrays.equals(magic, MAGIC)) {
				throw new IOException("not a first call model file: " + file.getFileName());
			}
			int headerLength = start.getInt();
			if (headerLength < 2 || headerLength > 1 << 20) {
				throw new IOException("bad first call model header length: " + headerLength);
			}
			ByteBuffer headerBytes = ByteBuffer.allocate(headerLength);
			readFully(channel, headerBytes);
			JsonNode header = json.readTree(new String(headerBytes.array(), StandardCharsets.UTF_8));
			int dim = header.path("dim").asInt();
			int hidden = header.path("hidden").asInt();
			if (dim < 1 || hidden < 1 || !header.path("heads").isArray()) {
				throw new IOException("bad first call model header: dim " + dim + ", hidden " + hidden);
			}
			List<Head> heads = new ArrayList<>();
			for (JsonNode node : header.path("heads")) {
				List<String> labels = new ArrayList<>();
				node.path("labels").forEach(label -> labels.add(label.asString()));
				int outputs = labels.size();
				ByteBuffer body = ByteBuffer.allocate((hidden * dim + hidden + outputs * hidden + outputs) * 4)
					.order(ByteOrder.LITTLE_ENDIAN);
				readFully(channel, body);
				heads.add(new Head(node.path("name").asString(), Kind.valueOf(node.path("kind").asString().toUpperCase(java.util.Locale.ROOT)),
						List.copyOf(labels), node.path("tau").asDouble(1), node.path("threshold").asDouble(1),
						node.path("max").asInt(outputs), floats(body, hidden * dim), floats(body, hidden),
						floats(body, outputs * hidden), floats(body, outputs)));
			}
			return new FirstCallModel(dim, hidden, heads);
		}
	}

	int dim() {
		return dim;
	}

	Head head(String name) {
		Head head = heads.get(name);
		if (head == null) {
			throw new IllegalStateException("the first call model has no head " + name);
		}
		return head;
	}

	/** The head's outputs: probabilities (softmax over the labels, or a sigmoid per label for {@code multi}). */
	double[] outputs(String name, float[] vector) {
		Head head = head(name);
		if (vector.length != dim) {
			throw new IllegalArgumentException("a vector of " + vector.length + ", the model takes " + dim);
		}
		double[] h = new double[hidden];
		for (int i = 0; i < hidden; i++) {
			double sum = head.b1()[i];
			int row = i * dim;
			for (int j = 0; j < dim; j++) {
				sum += head.w1()[row + j] * vector[j];
			}
			h[i] = Math.max(0, sum);
		}
		int outputs = head.labels().size();
		double[] z = new double[outputs];
		for (int k = 0; k < outputs; k++) {
			double sum = head.b2()[k];
			int row = k * hidden;
			for (int i = 0; i < hidden; i++) {
				sum += head.w2()[row + i] * h[i];
			}
			z[k] = sum;
		}
		if (head.kind() == Kind.MULTI) {
			for (int k = 0; k < outputs; k++) {
				z[k] = 1 / (1 + Math.exp(-z[k]));
			}
			return z;
		}
		double top = Arrays.stream(z).max().orElse(0);
		double total = 0;
		for (int k = 0; k < outputs; k++) {
			z[k] = Math.exp(z[k] - top);
			total += z[k];
		}
		for (int k = 0; k < outputs; k++) {
			z[k] /= total;
		}
		return z;
	}

	/** The route head's way: the most likely, but a way other than the last only at {@code tau} or more. */
	String route(float[] vector) {
		Head head = head("route");
		double[] p = outputs("route", vector);
		int best = 0;
		for (int k = 1; k < p.length; k++) {
			if (p[k] > p[best]) {
				best = k;
			}
		}
		int last = p.length - 1;
		return head.labels().get(best == last || p[best] >= head.tau() ? best : last);
	}

	/**
	 * A multi head's labels at the threshold or over, in the labels' order, at most {@code max} (as measured:
	 * {@code predict_eval.py} keeps the first two).
	 */
	List<String> labels(String name, float[] vector) {
		Head head = head(name);
		double[] p = outputs(name, vector);
		List<String> over = new ArrayList<>();
		for (int k = 0; k < p.length && over.size() < head.max(); k++) {
			if (p[k] >= head.threshold()) {
				over.add(head.labels().get(k));
			}
		}
		return List.copyOf(over);
	}

	/** A single head's most likely label. */
	String label(String name, float[] vector) {
		double[] p = outputs(name, vector);
		int best = 0;
		for (int k = 1; k < p.length; k++) {
			if (p[k] > p[best]) {
				best = k;
			}
		}
		return head(name).labels().get(best);
	}

	private static float[] floats(ByteBuffer body, int count) {
		float[] out = new float[count];
		body.asFloatBuffer().get(out);
		body.position(body.position() + count * 4);
		return out;
	}

	private static void readFully(FileChannel channel, ByteBuffer buffer) throws IOException {
		while (buffer.hasRemaining()) {
			if (channel.read(buffer) < 0) {
				throw new IOException("the first call model file ends early");
			}
		}
		buffer.flip();
	}

}
