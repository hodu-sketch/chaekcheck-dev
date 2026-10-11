package com.chaekcheck.library;

/**
 * Lending kind of a site: every library but the integrated portals has one, the portals have null (ADR-0071). Only
 * the KYOBO subscription screen reads it to decide holdings (ADR-0056); KYOBO subscription values come from the KYOBO
 * institution probe, and null there keeps the platform's own rule.
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
