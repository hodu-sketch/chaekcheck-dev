package com.chaekcheck.library;

/** Whether the user can join a target library, given the regions they sent. */
public enum Eligibility {
	/** The library has a NATIONWIDE rule: anyone can join. */
	OPEN,
	/** A RESIDENCE or WORK rule matches the user's regions. */
	ELIGIBLE,
	/**
	 * The library has region rules but the user sent no regions; or a library the user added by hand whose region
	 * rules do not match the regions sent (cycle 06, ADR-0050).
	 */
	UNKNOWN,
	/** The library has no rules we could verify (ADR-0013). It stays a target whatever the user's regions. */
	UNVERIFIED,
	/**
	 * Join rules not researched: a library for the members of one body or of unknown kind (ADR-0053,
	 * {@link JoinSurvey#onlyWhenChosen()}). Planned only when the user added it; never joinable.
	 */
	UNSURVEYED
}
