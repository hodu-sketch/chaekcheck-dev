package com.chaekcheck.aisearch.internal.vector;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.List;

/**
 * The book vectors of {@code book-vectors.bin} ({@link BookVectorFile} has the layout) searched as a funnel (user
 * 2026-10-30 "A. Java 깔때기로 개발해줘", docs/features/aisearch/reports/2026-10-30-vector-scaling.md). The heap holds the ids,
 * the scales and each row's sign bits (a bit is set where the int8 value is above 0: 128 bytes a book for 1,024
 * dimensions, about 1/8 of the rows); the int8 rows stay in the file, mapped read-only.
 * <ol>
 * <li>every book: hamming distance between its bits and the question's, the {@code hammingKeep} smallest kept;</li>
 * <li>those: the question (floats) against the bits read as +1 / -1, the {@code binaryKeep} highest kept;</li>
 * <li>those: the exact score {@code (int8 row . question) * scale} from the mapped file, as {@link BookVectorFile}.</li>
 * </ol>
 * A book with several rows (ids repeat) counts its best row and comes once; the keep counts are rows. The last step
 * scores like {@link BookVectorFile}, so a book that reaches it gets the same score; on 81,078 books
 * with 10,000 / 2,000 the result holds 99.2% of the exact top 120 (222 questions, measured). The file must not be
 * rewritten in place while mapped; a new model folder is a new file.
 */
final class BookVectorFunnel implements BookVectors {

	private static final ValueLayout.OfInt INT = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

	private static final ValueLayout.OfLong LONG = ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

	private static final ValueLayout.OfFloat FLOAT = ValueLayout.JAVA_FLOAT_UNALIGNED
		.withOrder(ByteOrder.LITTLE_ENDIAN);

	private final long[] ids;

	private final float[] scales;

	/** {@code words} longs a row; bit i of the row is bit (i % 64) of long (i / 64). */
	private final long[] bits;

	private final int dim;

	private final int words;

	private final int hammingKeep;

	private final int binaryKeep;

	private final int rowsPerBook;

	private final Arena arena;

	private final MemorySegment rows;

	private BookVectorFunnel(long[] ids, float[] scales, long[] bits, int dim, int hammingKeep, int binaryKeep,
			Arena arena, MemorySegment rows) {
		this.ids = ids;
		this.scales = scales;
		this.bits = bits;
		this.dim = dim;
		this.words = (dim + 63) / 64;
		this.hammingKeep = hammingKeep;
		this.binaryKeep = binaryKeep;
		this.rowsPerBook = BookVectors.rowsPerBook(ids);
		this.arena = arena;
		this.rows = rows;
	}

	static BookVectorFunnel open(Path file, int hammingKeep, int binaryKeep) throws IOException {
		if (hammingKeep < 1 || binaryKeep < 1) {
			throw new IllegalArgumentException("the funnel keeps " + hammingKeep + " / " + binaryKeep + " books");
		}
		Arena arena = Arena.ofShared();
		try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
			long size = channel.size();
			int head = BookVectorFile.MAGIC.length + 8;
			if (size < head) {
				throw new IOException("the book vector file ends early");
			}
			MemorySegment all = channel.map(FileChannel.MapMode.READ_ONLY, 0, size, arena);
			byte[] magic = all.asSlice(0, BookVectorFile.MAGIC.length).toArray(ValueLayout.JAVA_BYTE);
			if (!Arrays.equals(magic, BookVectorFile.MAGIC)) {
				throw new IOException("not a book vector file: " + file.getFileName());
			}
			int count = all.get(INT, BookVectorFile.MAGIC.length);
			int dim = all.get(INT, BookVectorFile.MAGIC.length + 4);
			if (count < 0 || dim < 1) {
				throw new IOException("bad book vector sizes: " + count + " x " + dim);
			}
			if (size < head + 12L * count + (long) count * dim) {
				throw new IOException("the book vector file ends early");
			}
			long[] ids = new long[count];
			MemorySegment.copy(all, LONG, head, ids, 0, count);
			float[] scales = new float[count];
			MemorySegment.copy(all, FLOAT, head + 8L * count, scales, 0, count);
			MemorySegment rows = all.asSlice(head + 12L * count, (long) count * dim);
			int words = (dim + 63) / 64;
			long[] bits = new long[Math.multiplyExact(count, words)];
			byte[] row = new byte[dim];
			for (int r = 0; r < count; r++) {
				MemorySegment.copy(rows, ValueLayout.JAVA_BYTE, (long) r * dim, row, 0, dim);
				int base = r * words;
				for (int i = 0; i < dim; i++) {
					if (row[i] > 0) {
						bits[base + (i >> 6)] |= 1L << (i & 63);
					}
				}
			}
			return new BookVectorFunnel(ids, scales, bits, dim, hammingKeep, binaryKeep, arena, rows);
		}
		catch (IOException | RuntimeException e) {
			arena.close();
			throw e;
		}
	}

	@Override
	public int count() {
		return ids.length;
	}

	@Override
	public int dim() {
		return dim;
	}

	@Override
	public int rowsPerBook() {
		return rowsPerBook;
	}

	@Override
	public List<Long> nearest(float[] query, int limit) {
		if (query.length != dim) {
			throw new IllegalArgumentException("query has " + query.length + " dimensions, the books " + dim);
		}
		if (limit < 1 || ids.length == 0) {
			return List.of();
		}
		int least = (int) Math.min((long) limit * rowsPerBook, Integer.MAX_VALUE);
		int[] kept = byHamming(query, Math.max(hammingKeep, least));
		kept = byBits(query, kept, Math.max(binaryKeep, least));
		float[] scores = new float[kept.length];
		byte[] row = new byte[dim];
		for (int c = 0; c < kept.length; c++) {
			int r = kept[c];
			MemorySegment.copy(rows, ValueLayout.JAVA_BYTE, (long) r * dim, row, 0, dim);
			float dot = 0;
			for (int i = 0; i < dim; i++) {
				dot += row[i] * query[i];
			}
			scores[c] = dot * scales[r];
		}
		int[] best = highest(scores, Math.min(least, kept.length));
		int[] rowsBestFirst = new int[best.length];
		for (int i = 0; i < best.length; i++) {
			rowsBestFirst[i] = kept[best[i]];
		}
		return BookVectors.books(ids, rowsBestFirst, limit);
	}

	/** Step 1: the rows of the {@code keep} smallest hamming distances (all rows when there are no more). */
	private int[] byHamming(float[] query, int keep) {
		int n = ids.length;
		if (keep >= n) {
			int[] all = new int[n];
			Arrays.setAll(all, i -> i);
			return all;
		}
		long[] mine = new long[words];
		for (int i = 0; i < dim; i++) {
			if (query[i] > 0) {
				mine[i >> 6] |= 1L << (i & 63);
			}
		}
		// distances are 0..dim, so counting them finds the cut without sorting
		short[] distance = new short[n];
		int[] counted = new int[dim + 1];
		for (int r = 0, base = 0; r < n; r++, base += words) {
			int d = 0;
			for (int w = 0; w < words; w++) {
				d += Long.bitCount(mine[w] ^ bits[base + w]);
			}
			distance[r] = (short) d;
			counted[d]++;
		}
		int cut = 0;
		int below = 0;
		while (below + counted[cut] < keep) {
			below += counted[cut++];
		}
		int[] out = new int[keep];
		int taken = 0;
		int atCut = keep - below;
		for (int r = 0; r < n && taken < keep; r++) {
			if (distance[r] < cut) {
				out[taken++] = r;
			}
			else if (distance[r] == cut && atCut > 0) {
				out[taken++] = r;
				atCut--;
			}
		}
		return out;
	}

	/** Step 2: of {@code rows}, the {@code keep} highest {@code query . (+1 where a bit is set, -1 elsewhere)}. */
	private int[] byBits(float[] query, int[] rows, int keep) {
		if (keep >= rows.length) {
			return rows;
		}
		// per byte of the bits, the sum of the query over each of the 256 patterns: 8 lookups a long
		int bytes = words * 8;
		float[] table = new float[bytes * 256];
		for (int p = 0; p < bytes; p++) {
			int base = p * 256;
			for (int v = 1; v < 256; v++) {
				int low = Integer.numberOfTrailingZeros(v);
				int i = p * 8 + low;
				table[base + v] = table[base + (v & (v - 1))] + (i < dim ? query[i] : 0);
			}
		}
		float total = 0;
		for (float x : query) {
			total += x;
		}
		float[] scores = new float[rows.length];
		for (int c = 0; c < rows.length; c++) {
			int base = rows[c] * words;
			float set = 0;
			for (int w = 0; w < words; w++) {
				long x = bits[base + w];
				int at = w * 8 * 256;
				for (int j = 0; j < 8; j++, at += 256) {
					set += table[at + (int) ((x >>> (j * 8)) & 0xFF)];
				}
			}
			scores[c] = 2 * set - total;
		}
		int[] best = highest(scores, keep);
		int[] out = new int[best.length];
		for (int i = 0; i < best.length; i++) {
			out[i] = rows[best[i]];
		}
		return out;
	}

	/** Indices of the {@code k} highest scores, highest first. */
	static int[] highest(float[] scores, int k) {
		// a min-heap of indices by score
		int[] heap = new int[k];
		int size = 0;
		for (int i = 0; i < scores.length; i++) {
			if (size < k) {
				heap[size] = i;
				int c = size++;
				while (c > 0 && scores[heap[(c - 1) / 2]] > scores[heap[c]]) {
					int p = (c - 1) / 2;
					int t = heap[p];
					heap[p] = heap[c];
					heap[c] = t;
					c = p;
				}
			}
			else if (k > 0 && scores[i] > scores[heap[0]]) {
				heap[0] = i;
				siftDown(scores, heap, 0, k);
			}
		}
		// take the smallest off the end until the heap is empty: highest first
		int[] out = new int[size];
		for (int end = size - 1; end >= 0; end--) {
			out[end] = heap[0];
			heap[0] = heap[end];
			siftDown(scores, heap, 0, end);
		}
		return out;
	}

	private static void siftDown(float[] scores, int[] heap, int c, int size) {
		while (true) {
			int l = 2 * c + 1;
			int m = c;
			if (l < size && scores[heap[l]] < scores[heap[m]]) {
				m = l;
			}
			if (l + 1 < size && scores[heap[l + 1]] < scores[heap[m]]) {
				m = l + 1;
			}
			if (m == c) {
				return;
			}
			int t = heap[m];
			heap[m] = heap[c];
			heap[c] = t;
			c = m;
		}
	}

	@Override
	public void close() {
		arena.close();
	}

}
