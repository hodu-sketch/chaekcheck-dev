package com.chaekcheck.foryou.internal.source;

import java.util.List;

/** One popular source of the daily build; each can be switched off (ADR-0147). */
public interface PopularSource {

	/** Reads every unit of the source (one per site). Never throws for a failing site: it is a failed read. */
	List<SourceRead> read();

}
