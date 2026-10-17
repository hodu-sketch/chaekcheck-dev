package com.chaekcheck.availability.internal.state;

/**
 * When a search was last refused with 503 {@code server_busy} ({@code SearchSlots}); the status signal is RED for
 * 10 seconds after it (spec R1). Memory store: {@link InMemoryLastTimeRecord}.
 */
public interface RefusalRecord extends LastTimeRecord {

}
