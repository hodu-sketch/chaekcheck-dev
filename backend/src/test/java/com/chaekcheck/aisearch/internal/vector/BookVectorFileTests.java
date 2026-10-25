package com.chaekcheck.aisearch.internal.vector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@link BookVectorFile}: the file layout make_book_vectors.py writes, and the nearest rows by dot product. */
class BookVectorFileTests {

	@TempDir
	Path dir;

	@Test
	void readsTheFileAndFindsTheNearestRowsFirst() throws IOException {
		// three books in 2 dimensions: 10 points along x, 20 along y, 30 between them
		Path file = write(new long[] { 10, 20, 30 }, new float[] { 1 / 127f, 1 / 127f, 0.7071f / 127 },
				new byte[] { 127, 0, 0, 127, 127, 127 }, 2);
		BookVectorFile books = BookVectorFile.read(file);

		assertThat(books.count()).isEqualTo(3);
		assertThat(books.nearest(new float[] { 1, 0 }, 2)).containsExactly(10L, 30L);
		assertThat(books.nearest(new float[] { 0, 1 }, 3)).containsExactly(20L, 30L, 10L);
		assertThat(books.nearest(new float[] { 0, 1 }, 0)).isEmpty();
		assertThatThrownBy(() -> books.nearest(new float[] { 1, 0, 0 }, 1)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void anotherFileOrAShortOneIsRefused() throws IOException {
		Path other = dir.resolve("other.bin");
		Files.writeString(other, "not vectors at all");
		assertThatThrownBy(() -> BookVectorFile.read(other)).isInstanceOf(IOException.class);

		Path whole = write(new long[] { 1, 2 }, new float[] { 1, 1 }, new byte[] { 1, 2, 3, 4 }, 2);
		byte[] bytes = Files.readAllBytes(whole);
		Path cut = dir.resolve("cut.bin");
		Files.write(cut, java.util.Arrays.copyOf(bytes, bytes.length - 1));
		assertThatThrownBy(() -> BookVectorFile.read(cut)).isInstanceOf(IOException.class);
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
