package com.chaekcheck.availability.internal.state;

/**
 * When the API last failed on the database ({@code LookupHealth.recordDbError()}); the status signal is GRAY with
 * {@code DB_ERROR} for 30 seconds after it (overall review #9). Memory store: {@link InMemoryLastTimeRecord}.
 */
public interface DbErrorRecord extends LastTimeRecord {

}
