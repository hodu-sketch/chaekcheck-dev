package com.chaekcheck.picks.internal;

/**
 * Told when a fetch has changed the stored list or its covers, so the shared cache is read again at once, not only
 * every 10 minutes. Inside the module only; not an application event.
 */
public interface PicksChanged {

	void picksChanged();

}
