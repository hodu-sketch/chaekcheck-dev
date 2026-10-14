/**
 * Usage log (cycle 15; plan ADR-0096, ADR-0097, ADR-0102): what people search, open and click, and how each library
 * site answered, kept in the {@code usagelog} schema for deciding what to build next. Nothing reads it in the service.
 * <p>
 * {@code catalog} and {@code availability} call {@link com.chaekcheck.usagelog.UsageLog} directly (no events); the web
 * reports what the server does not see to {@code POST /api/v1/usage-events}. Rows wait in memory (at most 10,000) and
 * are written every 5 seconds in one transaction with asynchronous commit; a failed batch is dropped and noted in
 * {@code dropped_batch}. <strong>Logging never holds up or breaks a search</strong>: every call only checks and queues,
 * and no exception reaches the caller.
 * <p>
 * Off by default ({@code chaekcheck.usagelog.enabled}); robots ({@code bot}, {@code crawler}, {@code spider} in the
 * User-Agent) are not recorded. No member number, User-Agent or cookie value is kept. Depends on {@code library}
 * (enabled library ids of the usage reports) and {@code auth} ({@code LoginStatusReader}).
 */
@ApplicationModule(allowedDependencies = { "library", "auth" })
package com.chaekcheck.usagelog;

import org.springframework.modulith.ApplicationModule;
