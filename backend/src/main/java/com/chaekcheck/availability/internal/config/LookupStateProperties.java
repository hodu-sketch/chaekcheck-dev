package com.chaekcheck.availability.internal.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Where the lookup state that lives in memory is kept (cycle 20, ADR-0133): the status memo, the last DB error time,
 * the {@code HostQueueBoard} and the {@code SearchSlots} refusal time. Each sits behind a small store interface and
 * {@code AvailabilityConfiguration} picks the implementation from this one value. Only {@link StateStore#MEMORY}
 * exists; any other value fails the binding, so the application does not start ({@code chaekcheck.lookup.state-store},
 * {@code CHAEKCHECK_LOOKUP_STATESTORE}; docs/maintenance/settings-index.md).
 * @param stateStore the store implementation
 */
@ConfigurationProperties("chaekcheck.lookup")
public record LookupStateProperties(StateStore stateStore) {

	public LookupStateProperties {
		if (stateStore == null) {
			throw new IllegalArgumentException("chaekcheck.lookup.state-store is required (memory)");
		}
	}

	public enum StateStore {

		/** This process's memory. Correct while the service runs as one instance. */
		MEMORY

	}

}
