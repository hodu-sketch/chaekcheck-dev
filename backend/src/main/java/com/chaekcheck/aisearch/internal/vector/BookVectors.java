package com.chaekcheck.aisearch.internal.vector;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import com.chaekcheck.aisearch.internal.config.AiSearchProperties;

/** The book vectors of {@code book-vectors.bin}, searched for the nearest ones. */
interface BookVectors extends AutoCloseable {

	int count();

	int dim();

	/** The {@code limit} ids nearest {@code query} (a normalised vector of {@link #dim()}), nearest first. */
	List<Long> nearest(float[] query, int limit);

	@Override
	default void close() {
	}

	/** The file opened the way {@code search} says. */
	static BookVectors open(Path file, AiSearchProperties.Search search) throws IOException {
		return switch (search.mode()) {
			case EXACT -> BookVectorFile.read(file);
			case FUNNEL -> BookVectorFunnel.open(file, search.hammingKeep(), search.binaryKeep());
		};
	}

}
