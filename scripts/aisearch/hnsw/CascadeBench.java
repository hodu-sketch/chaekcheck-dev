package com.chaekcheck.aisearch.internal.vector;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;

/**
 * Throwaway benchmark for docs/features/aisearch/reports/2026-10-30-vector-scaling.md (way A, the Java funnel). Not part of
 * the build: run_cascade_bench.sh copies it next to the real BookVectorFile source (same package, unchanged) and
 * compiles them together with javac.
 *
 * <pre>
 * bench MODEL_DIR queries.f32 WORK_DIR rounds n1 n2 [scales...]
 * </pre>
 *
 * For each size (scale 1 = the real 81,078 books, s &gt; 1 = the real rows tiled s times into a file in WORK_DIR):
 * <ol>
 * <li>baseline: the real {@code BookVectorFile.read} + {@code nearest(q, 120)} (whole file on the heap);</li>
 * <li>funnel: heap holds only the sign bits (16 longs per book), ids and scales; int8 rows stay in the file, mapped.
 * Step 1 hamming over every book, keep n1; step 2 asymmetric binary (float question . +-1 book) on those, keep n2;
 * step 3 exact {@code (int8 . q) * scale} on those from the mapped file, keep 120.</li>
 * </ol>
 * Times each step, single and two searches at once, and (real file only) recall against the baseline's top 120.
 */
public final class CascadeBench {

	static final int LIMIT = 120;

	static final int HEAD = 14;

	public static void main(String[] args) throws Exception {
		Path model = Path.of(args[1]);
		float[][] q = NearestBench.queries(Path.of(args[2]));
		Path work = Path.of(args[3]);
		int rounds = Integer.parseInt(args[4]);
		int n1 = Integer.parseInt(args[5]);
		int n2 = Integer.parseInt(args[6]);
		double[] scales = Arrays.stream(args).skip(7).mapToDouble(Double::parseDouble).toArray();
		Path real = model.resolve(BookVectorFile.NAME);
		for (double s : scales) {
			Path file = s == 1.0 ? real : tile(real, work, s);
			runSize(file, q, rounds, n1, n2, s == 1.0);
			if (!file.equals(real)) {
				Files.delete(file);
			}
		}
	}

	static void runSize(Path file, float[][] q, int rounds, int n1, int n2, boolean recall) throws Exception {
		Runtime rt = Runtime.getRuntime();
		List<List<Long>> gt = new ArrayList<>();
		// the 1M baseline needs two 1 GB arrays while reading (BookVectorFile.read); -Dbaseline.max skips it there
		int baselineMax = Integer.getInteger("baseline.max", Integer.MAX_VALUE);
		long rowsInFile = (Files.size(file) - HEAD) / (12 + 1024);
		if (rowsInFile <= baselineMax) { // baseline, then dropped before the funnel is loaded
			BookVectorFile books = BookVectorFile.read(file);
			for (int w = 0; w < 1; w++) { // one warm-up round: the 1M baseline alone takes minutes per round
				for (float[] v : q) {
					books.nearest(v, LIMIT);
				}
			}
			List<Double> ms = new ArrayList<>();
			for (int r = 0; r < rounds; r++) {
				for (float[] v : q) {
					long t = System.nanoTime();
					List<Long> ids = books.nearest(v, LIMIT);
					ms.add((System.nanoTime() - t) / 1e6);
					if (r == 0 && recall) {
						gt.add(ids);
					}
				}
			}
			System.out.printf("n=%d baseline nearest: %s%n", books.count(), NearestBench.stats(ms));
		}
		System.gc();
		long before = rt.totalMemory() - rt.freeMemory();
		long t0 = System.nanoTime();
		try (Funnel f = Funnel.open(file)) {
			long loadMs = (System.nanoTime() - t0) / 1_000_000;
			System.gc();
			long after = rt.totalMemory() - rt.freeMemory();
			System.out.printf("n=%d funnel load %d ms, heap +%.1f MB (bits %.1f MB)%n", f.n, loadMs,
					(after - before) / 1048576.0, f.bits.length * 8 / 1048576.0);
			for (int w = 0; w < 3; w++) {
				for (float[] v : q) {
					f.nearest(v, n1, n2, null);
				}
			}
			List<Double> total = new ArrayList<>();
			List<Double> s1 = new ArrayList<>();
			List<Double> s2 = new ArrayList<>();
			List<Double> s3 = new ArrayList<>();
			double[] steps = new double[3];
			double rec = 0;
			for (int r = 0; r < rounds; r++) {
				for (int i = 0; i < q.length; i++) {
					long t = System.nanoTime();
					long[] ids = f.nearest(q[i], n1, n2, steps);
					total.add((System.nanoTime() - t) / 1e6);
					s1.add(steps[0]);
					s2.add(steps[1]);
					s3.add(steps[2]);
					if (r == 0 && recall) {
						Set<Long> want = new HashSet<>(gt.get(i));
						int hit = 0;
						for (long id : ids) {
							hit += want.contains(id) ? 1 : 0;
						}
						rec += hit / (double) want.size();
					}
				}
			}
			System.out.printf("n=%d funnel n1=%d n2=%d total: %s%n", f.n, n1, n2, NearestBench.stats(total));
			System.out.printf("   step1 hamming: %s%n   step2 asym: %s%n   step3 int8: %s%n", NearestBench.stats(s1),
					NearestBench.stats(s2), NearestBench.stats(s3));
			if (recall) {
				System.out.printf("   recall of the baseline top %d: %.4f%n", LIMIT, rec / q.length);
			}
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
						f.nearest(v, n1, n2, null);
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
			System.out.printf("   two at once: %s%n", NearestBench.stats(pair));
		}
		System.gc();
	}

	/** The real file with its rows repeated to {@code scale} times the count (ids offset), same format. */
	static Path tile(Path real, Path work, double scale) throws IOException {
		byte[] all = Files.readAllBytes(real);
		ByteBuffer b = ByteBuffer.wrap(all).order(ByteOrder.LITTLE_ENDIAN);
		int count = b.getInt(6);
		int dim = b.getInt(10);
		int n = (int) Math.round(count * scale);
		Path out = work.resolve("tiled-" + n + ".bin");
		try (OutputStream os = new BufferedOutputStream(Files.newOutputStream(out), 1 << 20)) {
			ByteBuffer h = ByteBuffer.allocate(HEAD).order(ByteOrder.LITTLE_ENDIAN);
			h.put(all, 0, 6).putInt(n).putInt(dim);
			os.write(h.array());
			ByteBuffer x = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
			for (int r = 0; r < n; r++) {
				x.clear();
				x.putLong(b.getLong(HEAD + (r % count) * 8) + (long) (r / count) * 100_000_000L);
				os.write(x.array(), 0, 8);
			}
			for (int r = 0; r < n; r++) {
				os.write(all, HEAD + count * 8 + (r % count) * 4, 4);
			}
			int rowsAt = HEAD + count * 12;
			for (int r = 0; r < n; r++) {
				os.write(all, rowsAt + (r % count) * dim, dim);
			}
		}
		return out;
	}

	/** Way A: sign bits on the heap, int8 rows read from the mapped file only for the last step. */
	static final class Funnel implements AutoCloseable {

		final int n;

		final int dim;

		final int words;

		final long[] ids;

		final float[] scales;

		final long[] bits;

		final Arena arena;

		final MemorySegment rows;

		Funnel(int n, int dim, long[] ids, float[] scales, long[] bits, Arena arena, MemorySegment rows) {
			this.n = n;
			this.dim = dim;
			this.words = dim / 64;
			this.ids = ids;
			this.scales = scales;
			this.bits = bits;
			this.arena = arena;
			this.rows = rows;
		}

		static Funnel open(Path file) throws IOException {
			Arena arena = Arena.ofShared();
			try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
				MemorySegment all = ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size(), arena);
				int n = all.get(ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN), 6);
				int dim = all.get(ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN), 10);
				long[] ids = new long[n];
				float[] scales = new float[n];
				MemorySegment.copy(all, ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN), HEAD, ids,
						0, n);
				MemorySegment.copy(all, ValueLayout.JAVA_FLOAT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN),
						HEAD + 8L * n, scales, 0, n);
				MemorySegment rows = all.asSlice(HEAD + 12L * n, (long) n * dim);
				int words = dim / 64;
				long[] bits = new long[n * words];
				byte[] row = new byte[dim];
				for (int r = 0; r < n; r++) {
					MemorySegment.copy(rows, ValueLayout.JAVA_BYTE, (long) r * dim, row, 0, dim);
					for (int i = 0; i < dim; i++) {
						if (row[i] > 0) {
							bits[r * words + (i >> 6)] |= 1L << (i & 63);
						}
					}
				}
				return new Funnel(n, dim, ids, scales, bits, arena, rows);
			}
		}

		/** Top 120 ids; {@code steps} (if given) gets the three step times in ms. */
		long[] nearest(float[] q, int n1, int n2, double[] steps) {
			long t0 = System.nanoTime();
			// step 1: hamming to every book, keep the n1 smallest (distances are 0..dim, so a histogram finds the cut)
			long[] qb = new long[words];
			for (int i = 0; i < dim; i++) {
				if (q[i] > 0) {
					qb[i >> 6] |= 1L << (i & 63);
				}
			}
			short[] dist = new short[n];
			int[] hist = new int[dim + 1];
			for (int r = 0, o = 0; r < n; r++, o += words) {
				int d = 0;
				for (int w = 0; w < words; w++) {
					d += Long.bitCount(qb[w] ^ bits[o + w]);
				}
				dist[r] = (short) d;
				hist[d]++;
			}
			int cut = 0;
			int below = 0;
			while (below + hist[cut] < n1) {
				below += hist[cut++];
			}
			int[] c1 = new int[n1];
			int k1 = 0;
			int atCut = n1 - below;
			for (int r = 0; r < n && k1 < n1; r++) {
				if (dist[r] < cut) {
					c1[k1++] = r;
				}
				else if (dist[r] == cut && atCut > 0) {
					c1[k1++] = r;
					atCut--;
				}
			}
			long t1 = System.nanoTime();
			// step 2: asymmetric binary, sum of q over set bits read 8 bits at a time from a per-question table
			int bytes = dim / 8;
			float[] table = new float[bytes * 256];
			float sumQ = 0;
			for (int p = 0; p < bytes; p++) {
				for (int v = 0; v < 256; v++) {
					float s = 0;
					for (int j = 0; j < 8; j++) {
						if ((v & (1 << j)) != 0) {
							s += q[p * 8 + j];
						}
					}
					table[p * 256 + v] = s;
				}
			}
			for (float x : q) {
				sumQ += x;
			}
			float[] s2 = new float[k1];
			for (int c = 0; c < k1; c++) {
				int o = c1[c] * words;
				float s = 0;
				for (int w = 0; w < words; w++) {
					long x = bits[o + w];
					int base = w * 8 * 256;
					for (int j = 0; j < 8; j++) {
						s += table[base + j * 256 + (int) ((x >>> (j * 8)) & 0xFF)];
					}
				}
				s2[c] = 2 * s - sumQ; // q . (+1 for a set bit, -1 otherwise)
			}
			int[] c2 = topIndices(s2, Math.min(n2, k1));
			long t2 = System.nanoTime();
			// step 3: the server's exact score from the mapped int8 rows
			float[] s3 = new float[c2.length];
			byte[] row = new byte[dim];
			for (int c = 0; c < c2.length; c++) {
				int r = c1[c2[c]];
				MemorySegment.copy(rows, ValueLayout.JAVA_BYTE, (long) r * dim, row, 0, dim);
				float s = 0;
				for (int i = 0; i < dim; i++) {
					s += row[i] * q[i];
				}
				s3[c] = s * scales[r];
			}
			int[] best = topIndices(s3, Math.min(LIMIT, s3.length));
			long[] out = new long[best.length];
			for (int i = 0; i < best.length; i++) {
				out[i] = ids[c1[c2[best[i]]]];
			}
			long t3 = System.nanoTime();
			if (steps != null) {
				steps[0] = (t1 - t0) / 1e6;
				steps[1] = (t2 - t1) / 1e6;
				steps[2] = (t3 - t2) / 1e6;
			}
			return out;
		}

		/** Indices of the k highest scores (unordered), via a min-heap. */
		static int[] topIndices(float[] s, int k) {
			int[] heap = new int[k];
			int size = 0;
			for (int i = 0; i < s.length; i++) {
				if (size < k) {
					heap[size] = i;
					int c = size++;
					while (c > 0 && s[heap[(c - 1) / 2]] > s[heap[c]]) {
						int p = (c - 1) / 2;
						int t = heap[p];
						heap[p] = heap[c];
						heap[c] = t;
						c = p;
					}
				}
				else if (s[i] > s[heap[0]]) {
					heap[0] = i;
					int c = 0;
					while (true) {
						int l = 2 * c + 1;
						int m = c;
						if (l < k && s[heap[l]] < s[heap[m]]) {
							m = l;
						}
						if (l + 1 < k && s[heap[l + 1]] < s[heap[m]]) {
							m = l + 1;
						}
						if (m == c) {
							break;
						}
						int t = heap[m];
						heap[m] = heap[c];
						heap[c] = t;
						c = m;
					}
				}
			}
			return heap;
		}

		@Override
		public void close() {
			arena.close();
		}

	}

}
