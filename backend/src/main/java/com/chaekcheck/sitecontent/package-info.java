/**
 * Site content (cycle 16; ADR-0098, ADR-0099): the notices and the four documents (about, terms of service, privacy
 * policy, privacy collection notice), every version kept in the {@code sitecontent} schema and read without login.
 * <p>
 * A version is never edited: a new one is inserted with the next number per notice or document, by operations SQL
 * only (no write API). The current version is the one with the highest number whose effective day is on or before
 * today in Asia/Seoul; a version with a future day is not shown anywhere before that day. Nothing is held in memory,
 * so an inserted version shows on the next request.
 * <p>
 * Responsibilities: {@code GET /api/v1/notices}, {@code GET /api/v1/notices/{noticeId}} and
 * {@code GET /api/v1/documents/{documentKey}}; the public {@link com.chaekcheck.sitecontent.SiteDocuments} gives auth
 * the current version of the two sign-up documents (ADR-0099). Depends on no other module; only auth depends on it.
 * No events.
 */
@ApplicationModule(allowedDependencies = {})
package com.chaekcheck.sitecontent;

import org.springframework.modulith.ApplicationModule;
