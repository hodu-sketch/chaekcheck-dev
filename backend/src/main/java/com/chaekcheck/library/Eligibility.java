package com.chaekcheck.library;

/** Whether the user can join a target library, given the regions they sent. */
public enum Eligibility {
	/** The library has a NATIONWIDE rule: anyone can join. */
	OPEN,
	/** A RESIDENCE or WORK rule matches the user's regions. */
	ELIGIBLE,
	/** The library has region rules but the user sent no regions. */
	UNKNOWN,
	/** The library has no rules we could verify (ADR-0013). It stays a target whatever the user's regions. */
	UNVERIFIED
}
