/**
 * Live e-book availability: search and holding streams over the library sites (ADR-0016, ADR-0019), the lookup status
 * signal (cycle 20, ADR-0118) and the egress lanes (ADR-0130).
 * <p>
 * Public Java types: {@link com.chaekcheck.availability.LookupHealth} (the root exception handler reports DB errors)
 * and, since cycle 23 (ADR-0148), {@link com.chaekcheck.availability.RankedListings}, one page of a library site's
 * popular order for the foryou module's daily build.
 */
@org.springframework.modulith.ApplicationModule(allowedDependencies = { "catalog", "library", "usagelog" })
package com.chaekcheck.availability;
