package com.chaekcheck.library;

/**
 * How far a library's join rules were researched (ADR-0053). Registered libraries are all {@link #SURVEYED}; the
 * candidate libraries of cycle 07 carry the state their seed gave them. The screen picks its join notice by this
 * value.
 */
public enum JoinSurvey {
	/** The original join text was read; the eligibility rules (or none, for "not verified") are set from it. */
	SURVEYED,
	/** Researched, but no original text was found: no rules, shown like a registered "not verified" library. */
	NOT_FOUND,
	/**
	 * Looks like a library for the members of one body (school, university, company, government office, group).
	 * Planned only when the user added it (eligibility {@link Eligibility#UNSURVEYED}).
	 */
	MEMBERS_ONLY,
	/** Kind unknown, not researched. Planned only when the user added it. */
	NOT_SURVEYED;

	/** MEMBERS_ONLY and NOT_SURVEYED libraries are searched only when the user added them (ADR-0053). */
	public boolean onlyWhenChosen() {
		return this == MEMBERS_ONLY || this == NOT_SURVEYED;
	}

}
