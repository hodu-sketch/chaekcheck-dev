import { fetchResolutions, MAX_RESOLUTION_IDS } from '../../api/client'
import type { MyRegions, RegionResolution } from '../../api/types'
import { applyResolutions, type ResolutionOutcome } from './applyResolutions'
import type { StoredRegions } from './regionStorage'

/** Resolutions of any number of ids, 20 per request (API limit). */
export async function resolveAll(ids: number[]): Promise<RegionResolution[]> {
  const chunks: number[][] = []
  for (let i = 0; i < ids.length; i += MAX_RESOLUTION_IDS) chunks.push(ids.slice(i, i + MAX_RESOLUTION_IDS))
  const results = await Promise.all(chunks.map((chunk) => fetchResolutions(chunk)))
  return results.flat()
}

/** Resolves stored ids with `/api/v1/regions/resolutions` (spec 5.8): replaced, split and unknown ones. */
export async function resolveRegions(regions: StoredRegions): Promise<ResolutionOutcome> {
  const ids = [...new Set([...regions.residence, ...regions.work])]
  if (ids.length === 0) return { regions, notices: [], changed: false }
  return applyResolutions(regions, await resolveAll(ids))
}

export function toStored(regions: MyRegions): StoredRegions {
  return { residence: regions.residenceRegionIds, work: regions.workRegionIds }
}
