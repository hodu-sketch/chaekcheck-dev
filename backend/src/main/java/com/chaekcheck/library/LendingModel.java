package com.chaekcheck.library;

/**
 * Lending kind of an institution on the KYOBO subscription screen, from the KYOBO institution probe (ADR-0056).
 * Null (no value) keeps the platform's own rule.
 */
public enum LendingModel {
	/** Owned copies: the site shows holdings and loans, read as counts instead of "구독형 · 바로 대출". */
	OWNED,
	/** Subscription. */
	SUBSCRIPTION,
	/** Owned and subscription titles mixed. */
	MIXED,
	/** Rental (the probe's "대여"). */
	RENTAL
}
