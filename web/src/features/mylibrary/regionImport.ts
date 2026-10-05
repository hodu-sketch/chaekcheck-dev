import type { RegionResolution } from '../../api/types'
import type { StoredRegions } from '../region/regionStorage'

/**
 * S11 (cycle 06, ADR-0049; cycle 07, ADR-0057): after login, offers once to save this browser's regions and the
 * libraries it added to the account. The accounts that answered here (either answer) are kept in this browser so they
 * are not asked again; closing without an answer keeps nothing, so the next login asks again [확정]. One record for
 * both parts (cycle 07, dev decision): they are offered together in one question.
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

/** What S11 offers: a part only when the account has none of it and this browser has some (cycle 07 flow S11). */
export interface ImportParts {
  regions: boolean
  libraries: boolean
}

/**
 * The conditions of S11 (cycle 07 flow S11): signed in, the account's values read, not answered yet, and (the account
 * has no regions and this browser has some) or (the account has no added libraries and this browser has some). A part
 * the account already has is neither moved nor shown [확정].
 */
export function partsToOffer(input: {
  accountId: number | null
  serverRegions: StoredRegions | null
  serverLibraries: number[] | null
  browserRegions: StoredRegions
  browserLibraries: number[]
  answered: number[]
}): ImportParts | null {
  const { accountId, serverRegions, serverLibraries, browserRegions, browserLibraries, answered } = input
  if (accountId === null || serverRegions === null || serverLibraries === null || answered.includes(accountId)) {
    return null
  }
  const regions =
    serverRegions.residence.length === 0 &&
    serverRegions.work.length === 0 &&
    (browserRegions.residence.length > 0 || browserRegions.work.length > 0)
  const libraries = serverLibraries.length === 0 && browserLibraries.length > 0
  return regions || libraries ? { regions, libraries } : null
}

/**
 * The note after moving the libraries, when the account got fewer than were sent [안: dev decision]: over 20 (cannot
 * happen from this browser, which stops at 20) "20곳까지만 저장했어요" [확정]; otherwise some were switched off or
 * removed meanwhile and were skipped.
 */
export function libraryImportNote(sent: number, saved: number, max: number): string | null {
  if (saved >= sent) return null
  if (sent > max) return '20곳까지만 저장했어요'
  return `지금 쓸 수 없는 도서관 ${sent - saved}곳은 빼고 저장했어요`
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
