package com.chaekcheck.region;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Public API of the region module.
 */
public interface RegionDirectory {

	/**
	 * Returns, for each active SIGUNGU id, the set {itself, its parent SIDO}.
	 * @throws RegionNotUsableException if any id is not an active SIGUNGU (abolished, unknown or a SIDO id)
	 */
	Map<Long, Set<Long>> expandForMatching(Set<Long> sigunguIds);

	/**
	 * Resolves stored region ids, following replacement links to the end. Ordered by id.
	 */
	List<RegionResolution> resolve(Set<Long> ids);

}
