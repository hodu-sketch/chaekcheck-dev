package com.chaekcheck.aisearch.internal.vector;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * {@link BookVectorFunnel} against {@link BookVectorFile} on the real {@code book-vectors.bin} (only when
 * {@code AISEARCH_MODEL_DIR} names the folder and {@code AISEARCH_QUERIES} the question vectors of
 * docs/features/aisearch/reports/2026-10-29-hnsw.md, {@code queries.f32}: int32 count, int32 dim, float32 rows). The
 * production setting 10,000 / 2,000 found 99.2% of the exact top 120 on 81,078 books (2026-10-30).
 */
@EnabledIfEnvironmentVariable(named = "AISEARCH_QUERIES", matches = ".+")
class BookVectorFunnelModelTests {

	@Test
	void theFunnelFindsAlmostAllOfTheExactTop120() throws IOException {
		Path file = Path.of(System.getenv("AISEARCH_MODEL_DIR")).resolve(BookVectorFile.NAME);
		float[][] queries = queries(Path.of(System.getenv("AISEARCH_QUERIES")));
		BookVectorFile exact = BookVectorFile.read(file);
		try (BookVectorFunnel funnel = BookVectorFunnel.open(file, 10_000, 2_000)) {
			double found = 0;
			int sameFirst = 0;
			for (float[] q : queries) {
				List<Long> want = exact.nearest(q, 120);
				List<Long> got = funnel.nearest(q, 120);
				Set<Long> hit = new HashSet<>(got);
				hit.retainAll(want);
				found += hit.size() / (double) want.size();
				sameFirst += got.get(0).equals(want.get(0)) ? 1 : 0;
			}
			double recall = found / queries.length;
			System.out.printf("funnel on %d books, %d questions: top 120 recall %.4f, same first book %d%n",
					funnel.count(), queries.length, recall, sameFirst);
			assertThat(recall).isGreaterThan(0.98);
		}
	}

	private static float[][] queries(Path file) throws IOException {
		ByteBuffer b = ByteBuffer.wrap(Files.readAllBytes(file)).order(ByteOrder.LITTLE_ENDIAN);
		float[][] q = new float[b.getInt()][b.getInt()];
		for (float[] row : q) {
			for (int i = 0; i < row.length; i++) {
				row[i] = b.getFloat();
			}
		}
		return q;
	}

}
