package com.chaekcheck.availability.internal.fetch;

import java.net.URI;
import java.time.Instant;

import com.chaekcheck.availability.internal.adapter.ParsedPage;

/** A parsed library page and when it was fetched from the site (server clock). */
public record FetchedPage(ParsedPage parsed, URI searchUri, Instant fetchedAt) {
}
