package com.chaekcheck.collection.internal.importer;

import java.util.Map;

/** Collection DB library id → service library id (ADR-0052). */
@FunctionalInterface
interface CollectionLinks {

	Map<Long, Long> all();

}
