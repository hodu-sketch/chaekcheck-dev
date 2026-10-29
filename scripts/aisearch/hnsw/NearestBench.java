package com.chaekcheck.aisearch.internal.vector;

import java.io.BufferedWriter;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;

/**
 * Throwaway benchmark for docs/aisearch/reports/2026-10-29-hnsw.md. Not part of the build: run_nearest_bench.sh copies
 * it next to the real BookVectorFile / OnnxQuestionEncoder / QuestionEncoder sources (same package, unchanged) and
 * compiles them together with javac.
 *
 * <pre>
 * encode MODEL_DIR questions.txt queries.f32        question texts -> int32 count, int32 dim, float32 rows (LE)
 * bench  MODEL_DIR queries.f32 OUT_DIR rounds [scales...]
 *        times BookVectorFile.nearest(q, 120) as the server calls it; writes OUT_DIR/brute-times-&lt;n&gt;.tsv,
 *        OUT_DIR/brute-gt.tsv (the server's exact top 120 ids per query, real file only) and prints a summary.
 *        A scale s &gt; 1 tiles the real rows s times (ids offset) to time 300k / 1M books.
 * </pre>
 */
public final class NearestBench {

	static final int LIMIT = 120; // vectorDepth 60 x 2 (application.yml, AiSearchFlow)

	public static void main(String[] args) throws Exception {
		switch (args[0]) {
			case "encode" -> encode(Path.of(args[1]), Path.of(args[2]), Path.of(args[3]));
			case "bench" -> bench(Path.of(args[1]), Path.of(args[2]), Path.of(args[3]), Integer.parseInt(args[4]),
					Arrays.stream(args).skip(5).mapToDouble(Double::parseDouble).toArray());
			default -> throw new IllegalArgumentException(args[0]);
		}
	}

	static void encode(Path dir, Path questions, Path out) throws Exception {
		List<String> lines = Files.readAllLines(questions, StandardCharsets.UTF_8)
			.stream()
			.filter(s -> !s.isBlank())
			.toList();
		try (OnnxQuestionEncoder enc = new OnnxQuestionEncoder(dir, 2);
				DataOutputStream os = new DataOutputStream(Files.newOutputStream(out))) {
			float[] first = enc.encode(lines.get(0));
			ByteBuffer head = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
			head.putInt(lines.size()).putInt(first.length);
			os.write(head.array());
			long t = System.nanoTime();
			for (String q : lines) {
				float[] v = enc.encode(q);
				ByteBuffer b = ByteBuffer.allocate(v.length * 4).order(ByteOrder.LITTLE_ENDIAN);
				for (float f : v) {
					b.putFloat(f);
				}
				os.write(b.array());
			}
			System.out.printf("encoded %d questions, %.1f ms each%n", lines.size(),
					(System.nanoTime() - t) / 1e6 / lines.size());
		}
	}

	static float[][] queries(Path file) throws IOException {
		ByteBuffer b = ByteBuffer.wrap(Files.readAllBytes(file)).order(ByteOrder.LITTLE_ENDIAN);
		int n = b.getInt();
		int dim = b.getInt();
		float[][] q = new float[n][dim];
		for (float[] row : q) {
			for (int i = 0; i < dim; i++) {
				row[i] = b.getFloat();
			}
		}
		return q;
	}

	static void bench(Path dir, Path queryFile, Path outDir, int rounds, double[] scales) throws Exception {
		float[][] q = queries(queryFile);
		Runtime rt = Runtime.getRuntime();
		System.gc();
		long before = rt.totalMemory() - rt.freeMemory();
		long t0 = System.nanoTime();
		BookVectorFile real = BookVectorFile.read(dir.resolve(BookVectorFile.NAME));
		long loadMs = (System.nanoTime() - t0) / 1_000_000;
		System.gc();
		long after = rt.totalMemory() - rt.freeMemory();
		System.out.printf("file: %d books x %d, load %d ms, heap +%.1f MB%n", real.count(), real.dim(), loadMs,
				(after - before) / 1048576.0);
		// ground truth: the server's own top 120 (exact int8 dot product)
		try (BufferedWriter w = Files.newBufferedWriter(outDir.resolve("brute-gt.tsv"))) {
			for (int i = 0; i < q.length; i++) {
				List<Long> ids = real.nearest(q[i], LIMIT);
				w.write(i + "\t" + String.join(",", ids.stream().map(String::valueOf).toList()) + "\n");
			}
		}
		run(real, q, outDir, rounds);
		for (double s : scales) {
			BookVectorFile big = tiled(dir, real.count(), real.dim(), s);
			run(big, q, outDir, rounds);
			big = null;
			System.gc();
		}
	}

	/** The real rows repeated to {@code scale} times the count (ids offset); same arithmetic per row. */
	static BookVectorFile tiled(Path dir, int count, int dim, double scale) throws IOException {
		int n = (int) Math.round(count * scale);
		byte[] all = Files.readAllBytes(dir.resolve(BookVectorFile.NAME));
		ByteBuffer b = ByteBuffer.wrap(all).order(ByteOrder.LITTLE_ENDIAN);
		int head = 14;
		long[] ids = new long[n];
		float[] scales = new float[n];
		byte[] rows = new byte[n * dim];
		int rowsAt = head + count * 12;
		for (int r = 0; r < n; r++) {
			int src = r % count;
			ids[r] = b.getLong(head + src * 8) + (long) (r / count) * 100_000_000L;
			scales[r] = b.getFloat(head + count * 8 + src * 4);
			System.arraycopy(all, rowsAt + src * dim, rows, r * dim, dim);
		}
		return new BookVectorFile(ids, scales, rows, dim);
	}

	static void run(BookVectorFile books, float[][] q, Path outDir, int rounds) throws Exception {
		for (int w = 0; w < 3; w++) { // warm up the JIT as a running server is
			for (float[] v : q) {
				books.nearest(v, LIMIT);
			}
		}
		List<Double> ms = new ArrayList<>();
		for (int r = 0; r < rounds; r++) {
			for (float[] v : q) {
				long t = System.nanoTime();
				books.nearest(v, LIMIT);
				ms.add((System.nanoTime() - t) / 1e6);
			}
		}
		// two searches at once (two request threads), as two users asking together
		List<Double> pair = new ArrayList<>();
		for (int i = 0; i + 1 < q.length; i += 2) {
			CountDownLatch go = new CountDownLatch(1);
			double[] got = new double[2];
			Thread[] th = new Thread[2];
			for (int k = 0; k < 2; k++) {
				int kk = k;
				float[] v = q[i + k];
				th[k] = Thread.ofPlatform().start(() -> {
					try {
						go.await();
					}
					catch (InterruptedException e) {
						return;
					}
					long t = System.nanoTime();
					books.nearest(v, LIMIT);
					got[kk] = (System.nanoTime() - t) / 1e6;
				});
			}
			go.countDown();
			for (Thread t : th) {
				t.join();
			}
			pair.add(got[0]);
			pair.add(got[1]);
		}
		try (BufferedWriter w = Files.newBufferedWriter(outDir.resolve("brute-times-" + books.count() + ".tsv"))) {
			for (double d : ms) {
				w.write(String.format("%.3f%n", d));
			}
		}
		System.out.printf("n=%d single: %s | two at once: %s%n", books.count(), stats(ms), stats(pair));
	}

	static String stats(List<Double> xs) {
		double[] a = xs.stream().mapToDouble(Double::doubleValue).sorted().toArray();
		return String.format("runs %d median %.1f p90 %.1f p99 %.1f max %.1f ms", a.length, pct(a, 50), pct(a, 90),
				pct(a, 99), a[a.length - 1]);
	}

	static double pct(double[] a, double p) {
		return a[(int) Math.min(a.length - 1, Math.ceil(p / 100 * a.length) - 1)];
	}

}
