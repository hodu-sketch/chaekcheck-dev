package com.chaekcheck.aisearch.internal.vector;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.PriorityQueue;

/**
 * The book vectors, read whole into memory (about 31 MB for 31,000 books): the file {@code book-vectors.bin} that
 * {@code scripts/aisearch/make_book_vectors.py} writes, little-endian: {@code "CCBV1\n"}, count and dimension
 * (int32), the book ids (int64), one scale per row (float32), then the rows (int8). A row times its scale is the
 * book's normalised KURE-v1 vector. Every row is scored ({@code chaekcheck.aisearch.vector.search.mode: exact});
 * {@link BookVectorFunnel} reads the same file keeping only the sign bits on the heap.
 */
final class BookVectorFile implements BookVectors {

	static final String NAME = "book-vectors.bin";

	static final byte[] MAGIC = "CCBV1\n".getBytes(StandardCharsets.US_ASCII);

	private final long[] ids;

	private final float[] scales;

	private final byte[] rows;

	private final int dim;

	BookVectorFile(long[] ids, float[] scales, byte[] rows, int dim) {
		if (scales.length != ids.length || rows.length != (long) ids.length * dim) {
			throw new IllegalArgumentException("ids, scales and rows do not agree");
		}
		this.ids = ids;
		this.scales = scales;
		this.rows = rows;
		this.dim = dim;
	}

	static BookVectorFile read(Path file) throws IOException {
		try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
			ByteBuffer head = ByteBuffer.allocate(MAGIC.length + 8).order(ByteOrder.LITTLE_ENDIAN);
			readFully(channel, head);
			byte[] magic = new byte[MAGIC.length];
			head.get(magic);
			if (!Arrays.equals(magic, MAGIC)) {
				throw new IOException("not a book vector file: " + file.getFileName());
			}
			int count = head.getInt();
			int dim = head.getInt();
			if (count < 0 || dim < 1 || (long) count * dim > Integer.MAX_VALUE - 8) {
				throw new IOException("bad book vector sizes: " + count + " x " + dim);
			}
			ByteBuffer body = ByteBuffer.allocate(count * 8 + count * 4 + count * dim).order(ByteOrder.LITTLE_ENDIAN);
			readFully(channel, body);
			long[] ids = new long[count];
			body.asLongBuffer().get(ids);
			body.position(count * 8);
			float[] scales = new float[count];
			body.slice().order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(scales);
			body.position(count * 12);
			byte[] rows = new byte[count * dim];
			body.get(rows);
			return new BookVectorFile(ids, scales, rows, dim);
		}
	}

	private static void readFully(FileChannel channel, ByteBuffer buffer) throws IOException {
		while (buffer.hasRemaining()) {
			if (channel.read(buffer) < 0) {
				throw new IOException("the book vector file ends early");
			}
		}
		buffer.flip();
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
	public List<Long> nearest(float[] query, int limit) {
		if (query.length != dim) {
			throw new IllegalArgumentException("query has " + query.length + " dimensions, the books " + dim);
		}
		if (limit < 1 || ids.length == 0) {
			return List.of();
		}
		// a min-heap of the best rows so far: [score, row] packed as the row's index into a parallel array
		PriorityQueue<int[]> best = new PriorityQueue<>(limit + 1,
				(a, b) -> Float.compare(Float.intBitsToFloat(a[0]), Float.intBitsToFloat(b[0])));
		for (int row = 0; row < ids.length; row++) {
			int base = row * dim;
			float dot = 0;
			for (int i = 0; i < dim; i++) {
				dot += rows[base + i] * query[i];
			}
			float score = dot * scales[row];
			if (best.size() < limit) {
				best.add(new int[] { Float.floatToIntBits(score), row });
			}
			else if (score > Float.intBitsToFloat(best.peek()[0])) {
				best.poll();
				best.add(new int[] { Float.floatToIntBits(score), row });
			}
		}
		List<Long> out = new ArrayList<>(best.size());
		while (!best.isEmpty()) {
			out.add(ids[best.poll()[1]]);
		}
		java.util.Collections.reverse(out);
		return out;
	}

}
