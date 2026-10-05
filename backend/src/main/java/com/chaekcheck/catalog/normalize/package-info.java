/**
 * Normalizing rules for the collected catalog: titles, contributors, ISBNs and publication dates (research
 * search-and-normalization 4절). The catalog search cleans the search words with them, and the {@code collection}
 * import cleans the collected rows with the same code: one copy of the rules (ADR-0031, ADR-0060).
 */
@NamedInterface("normalize")
package com.chaekcheck.catalog.normalize;

import org.springframework.modulith.NamedInterface;
