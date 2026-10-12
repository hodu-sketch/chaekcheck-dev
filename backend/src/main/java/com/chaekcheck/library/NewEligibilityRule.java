package com.chaekcheck.library;

/**
 * One eligibility rule of a {@link NewLibrary}: the columns of the seed's eligibility rule CSV (ADR-0004). The
 * database checks the values.
 * @param basis RESIDENCE, WORK, SCHOOL or AFFILIATION
 * @param scopeType REGION, INSTITUTION or NATIONWIDE
 * @param scopeRegionId a region module id for a REGION rule, else null
 */
public record NewEligibilityRule(String basis, String scopeType, Long scopeRegionId) {
}
