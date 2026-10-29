package com.chaekcheck.aisearch.internal.vector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.chaekcheck.aisearch.internal.config.AiSearchProperties;

/**
 * {@link BookVectorFunnel}: the same file as {@link BookVectorFile}, the same answer when nothing is cut, and close to
 * it when the first two steps cut.
 */
class BookVectorFunnelTests {

	@TempDir
	Path dir;

	@Test
	void keepingEveryBookGivesTheExactAnswer() throws IOException {
		Path file = write(new long[] { 10, 20, 30 }, new float[] { 1 / 127f, 1 / 127f, 0.7071f / 127 },
				new byte[] { 127, 0, 0, 127, 127, 127 }, 2);
		try (BookVectorFunnel books = BookVectorFunnel.open(file, 100, 100)) {
			assertThat(books.count()).isEqualTo(3);
			assertThat(books.dim()).isEqualTo(2);
			assertThat(books.nearest(new float[] { 1, 0 }, 2)).containsExactly(10L, 30L);
			assertThat(books.nearest(new float[] { 0, 1 }, 3)).containsExactly(20L, 30L, 10L);
			assertThat(books.nearest(new float[] { 0, 1 }, 0)).isEmpty();
			assertThatThrownBy(() -> books.nearest(new float[] { 1, 0, 0 }, 1))
				.isInstanceOf(IllegalArgumentException.class);
		}
	}

	@Test
	void cuttingKeepsTheNearestBooksInTheExactOrder() throws IOException {
		// 3,000 random books in 128 dimensions; the questions are near some of them, as real questions are near books
		Random random = new Random(7);
		int n = 3000;
		int dim = 128;
		long[] ids = new long[n];
		float[] scales = new float[n];
		byte[] rows = new byte[n * dim];
		float[][] vectors = new float[n][dim];
		for (int r = 0; r < n; r++) {
			ids[r] = 1000 + r;
			float[] v = unit(random, dim);
			vectors[r] = v;
			float max = 0;
			for (float x : v) {
				max = Math.max(max, Math.abs(x));
			}
			scales[r] = max / 127;
			for (int i = 0; i < dim; i++) {
				rows[r * dim + i] = (byte) Math.round(v[i] / scales[r]);
			}
		}
		Path file = write(ids, scales, rows, dim);
		BookVectorFile exact = BookVectorFile.read(file);
		// random vectors spread more evenly than KURE's, so the funnel keeps a larger share here than in production
		try (BookVectorFunnel funnel = BookVectorFunnel.open(file, 1500, 500)) {
			double found = 0;
			for (int t = 0; t < 30; t++) {
				float[] q = vectors[random.nextInt(n)].clone();
				float[] noise = unit(random, dim);
				for (int i = 0; i < dim; i++) {
					q[i] = q[i] + 0.5f * noise[i];
				}
				q = normalised(q);
				List<Long> want = exact.nearest(q, 20);
				List<Long> got = funnel.nearest(q, 20);
				assertThat(got).hasSize(20);
				assertThat(got.get(0)).isEqualTo(want.get(0));
				// what both found is in the same order: the last step is the exact score
				List<Long> common = got.stream().filter(want::contains).toList();
				assertThat(common).isEqualTo(want.stream().filter(common::contains).toList());
				Set<Long> hit = new HashSet<>(got);
				hit.retainAll(want);
				found += hit.size() / 20.0;
			}
			assertThat(found / 30).isGreaterThan(0.9);
		}
	}

	@Test
	void theSettingPicksTheWay() throws IOException {
		Path file = write(new long[] { 1, 2 }, new float[] { 1, 1 }, new byte[] { 1, 2, 3, 4 }, 2);
		try (BookVectors exact = BookVectors.open(file,
				new AiSearchProperties.Search(AiSearchProperties.SearchMode.EXACT, 10, 10));
				BookVectors funnel = BookVectors.open(file,
						new AiSearchProperties.Search(AiSearchProperties.SearchMode.FUNNEL, 10, 10))) {
			assertThat(exact).isInstanceOf(BookVectorFile.class);
			assertThat(funnel).isInstanceOf(BookVectorFunnel.class);
			assertThat(funnel.nearest(new float[] { 0, 1 }, 2)).isEqualTo(exact.nearest(new float[] { 0, 1 }, 2));
		}
	}

	@Test
	void anotherFileOrAShortOneIsRefused() throws IOException {
		Path other = dir.resolve("other.bin");
		Files.writeString(other, "not vectors at all");
		assertThatThrownBy(() -> BookVectorFunnel.open(other, 10, 10)).isInstanceOf(IOException.class);

		Path whole = write(new long[] { 1, 2 }, new float[] { 1, 1 }, new byte[] { 1, 2, 3, 4 }, 2);
		byte[] bytes = Files.readAllBytes(whole);
		Path cut = dir.resolve("cut.bin");
		Files.write(cut, java.util.Arrays.copyOf(bytes, bytes.length - 1));
		assertThatThrownBy(() -> BookVectorFunnel.open(cut, 10, 10)).isInstanceOf(IOException.class);
		assertThatThrownBy(() -> BookVectorFunnel.open(whole, 0, 10)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void highestComesHighestFirst() {
		float[] scores = { 0.1f, 0.9f, 0.5f, 0.7f, 0.3f };
		assertThat(BookVectorFunnel.highest(scores, 3)).containsExactly(1, 3, 2);
		assertThat(BookVectorFunnel.highest(scores, 9)).containsExactly(1, 3, 2, 4, 0);
	}

	private static float[] unit(Random random, int dim) {
		float[] v = new float[dim];
		for (int i = 0; i < dim; i++) {
			v[i] = (float) random.nextGaussian();
		}
		return normalised(v);
	}

	private static float[] normalised(float[] v) {
		double sum = 0;
		for (float x : v) {
			sum += x * x;
		}
		float norm = (float) Math.sqrt(sum);
		for (int i = 0; i < v.length; i++) {
			v[i] /= norm;
		}
		return v;
	}

	private Path write(long[] ids, float[] scales, byte[] rows, int dim) throws IOException {
		ByteBuffer buffer = ByteBuffer.allocate(6 + 8 + ids.length * 12 + rows.length).order(ByteOrder.LITTLE_ENDIAN);
		buffer.put("CCBV1\n".getBytes(StandardCharsets.US_ASCII)).putInt(ids.length).putInt(dim);
		for (long id : ids) {
			buffer.putLong(id);
		}
		for (float scale : scales) {
			buffer.putFloat(scale);
		}
		buffer.put(rows);
		Path file = dir.resolve("book-vectors-" + ids.length + ".bin");
		Files.write(file, buffer.array());
		return file;
	}

}
