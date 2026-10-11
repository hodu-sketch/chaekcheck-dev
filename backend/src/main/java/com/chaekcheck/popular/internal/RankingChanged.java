package com.chaekcheck.popular.internal;

/**
 * Told when a fetch has stored a month or its covers, so the shared cache is read again at once (ADR-0074), not only
 * every 10 minutes. Inside the module only; not an application event.
 */
public interface RankingChanged {

	void rankingChanged();

}
