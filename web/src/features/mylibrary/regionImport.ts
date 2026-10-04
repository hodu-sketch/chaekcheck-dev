import type { RegionResolution } from '../../api/types'
import type { StoredRegions } from '../region/regionStorage'

/**
 * S11 (cycle 06, ADR-0049): after login, offers once to save this browser's regions to the account. The accounts that
 * answered here (either answer) are kept in this browser so they are not asked again; closing without an answer keeps
 * nothing, so the next login asks again [확정].
 */
export const REGION_IMPORT_KEY = 'chaekcheck.regionImport.v1'

export function answeredAccounts(storage: Storage = localStorage): number[] {
  try {
    const parsed: unknown = JSON.parse(storage.getItem(REGION_IMPORT_KEY) ?? '[]')
    return Array.isArray(parsed) ? parsed.filter((id): id is number => Number.isSafeInteger(id) && id > 0) : []
  } catch {
    return []
  }
}

export function rememberAnswer(accountId: number, storage: Storage = localStorage): void {
  try {
    const ids = answeredAccounts(storage)
    if (!ids.includes(accountId)) storage.setItem(REGION_IMPORT_KEY, JSON.stringify([...ids, accountId]))
  } catch {
    // storage blocked: the question comes again next time
  }
}

/** All four conditions of S11 (flow S11). */
export function shouldOfferImport(input: {
  accountId: number | null
  serverRegions: StoredRegions | null
  browserRegions: StoredRegions
  answered: number[]
}): boolean {
  const { accountId, serverRegions, browserRegions, answered } = input
  if (accountId === null || serverRegions === null) return false
  const serverEmpty = serverRegions.residence.length === 0 && serverRegions.work.length === 0
  const browserHas = browserRegions.residence.length > 0 || browserRegions.work.length > 0
  return serverEmpty && browserHas && !answered.includes(accountId)
}

export interface ImportPlan {
  /** What [계정에 저장] sends: replaced regions swapped for their new id, split and unknown ones left out. */
  regions: StoredRegions
  /** New names of regions that were replaced ("행정구역이 바뀌어 △△로 저장돼요"). */
  replacedTo: string[]
  /** Names of split or unknown regions, left out ("다시 골라 주세요" after saving); null when no name is known. */
  dropped: Array<string | null>
}

/** Turns the browser's regions and their resolutions into what S11 saves [확정] (사용자 승인, 2026-10-04). */
export function planImport(regions: StoredRegions, resolutions: RegionResolution[]): ImportPlan {
  const byId = new Map(resolutions.map((resolution) => [resolution.id, resolution]))
  const replacedTo: string[] = []
  const dropped: Array<string | null> = []
  const seen = new Set<number>()

  const mapField = (ids: number[]): number[] => {
    const next: number[] = []
    for (const id of ids) {
      const resolution = byId.get(id)
      if (!resolution || resolution.status === 'ACTIVE') {
        next.push(id)
        continue
      }
      const first = !seen.has(id)
      seen.add(id)
      if (resolution.status === 'REPLACED' && resolution.replacements.length === 1) {
        next.push(resolution.replacements[0].id)
        if (first) replacedTo.push(resolution.replacements[0].label)
      } else if (first) {
        dropped.push(resolution.label)
      }
    }
    return [...new Set(next)]
  }

  return { regions: { residence: mapField(regions.residence), work: mapField(regions.work) }, replacedTo, dropped }
}
