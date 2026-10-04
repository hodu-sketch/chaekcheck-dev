package com.chaekcheck.auth;

/**
 * Published when an account has been deleted (withdrawal, provider unlink, inactivity clean-up). Carries only the
 * account number, never the entity or the provider's user number (ADR-0041).
 */
public record AccountDeleted(AccountId accountId) {
}
