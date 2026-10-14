/**
 * Login and account (cycle 05): Naver and Kakao social login, JWT access tokens with refresh tokens in the
 * database (ADR-0038), sign-up consent, logout and withdrawal (ADR-0043), the provider unlink webhooks, and the
 * application-wide {@code SecurityFilterChain} (ADR-0044). Keeps only the account number, login methods, consents
 * and refresh tokens (ADR-0036, ADR-0039).
 * <p>
 * The whole module is switched by {@code chaekcheck.auth.enabled} (default off, ADR-0046). When it is off the
 * application behaves as in cycle 04: no security filter, no {@code /api/v1/auth/**} or {@code /api/v1/me}.
 * This module depends only on {@code sitecontent}'s public API {@code SiteDocuments} for the sign-up document versions
 * (cycle 16, ADR-0099; until then it depended on no module, AC-18). Since cycle 06 only {@code mylibrary} depends on it, and only on
 * its public types (ADR-0048): {@code AccountId} (filled into controller parameters from the signed-in account),
 * {@code AccountDeleted}, {@code AccountPresence} (the account row lock, ADR-0051) and
 * {@code ConditionalOnAuthEnabled}.
 */
package com.chaekcheck.auth;
