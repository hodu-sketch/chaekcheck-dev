/**
 * "내 도서관" of a signed-in account (cycle 06, ADR-0048): its residence and work regions and the libraries it added by
 * hand, kept on the server so every browser of the account uses them (ADR-0049). REST only, no public Java types.
 * <p>
 * Depends only on public types: {@code auth} for the account ({@code AccountId} parameters, the row lock
 * {@code AccountPresence}, {@code AccountDeleted} to delete the rows of a deleted account), {@code library} to check
 * that an added library is enabled, and {@code region} to check the saved regions. The search modules do not know
 * this one: the web sends the saved values with each search (ADR-0037). Every bean follows the login switch
 * ({@code chaekcheck.auth.enabled}); the tables exist either way.
 */
@ApplicationModule(allowedDependencies = { "auth", "library", "region" })
package com.chaekcheck.mylibrary;

import org.springframework.modulith.ApplicationModule;
