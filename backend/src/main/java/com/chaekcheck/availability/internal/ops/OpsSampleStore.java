package com.chaekcheck.availability.internal.ops;

import java.time.Instant;

/** Where the 10-second samples go: {@code availability.lookup_sample} and its month partitions. */
public interface OpsSampleStore {

	void insert(OpsSample sample);

	/** Makes the current month's partition and {@code monthsAhead} more when missing; returns how many were made. */
	int ensurePartitions(int monthsAhead);

	/** Drops the month partitions that end at or before {@code cutoff}; returns how many were dropped. */
	int dropBefore(Instant cutoff);

}
