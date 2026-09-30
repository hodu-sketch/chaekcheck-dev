// "My regions" live only in this browser (AC-6). Never sent to the server for storage.

export interface StoredRegions {
  residence: number[]
  work: number[]
}

export const MAX_REGIONS_PER_FIELD = 20

/** [안] key name; the version suffix lets a later format change ignore old data. */
export const REGION_STORAGE_KEY = 'chaekcheck.regions.v1'

export const EMPTY_REGIONS: StoredRegions = { residence: [], work: [] }

function cleanIds(value: unknown): number[] {
  if (!Array.isArray(value)) return []
  const ids = value.filter((id): id is number => Number.isSafeInteger(id) && id > 0)
  return [...new Set(ids)].slice(0, MAX_REGIONS_PER_FIELD)
}

export function loadRegions(storage: Storage = localStorage): StoredRegions {
  try {
    const raw = storage.getItem(REGION_STORAGE_KEY)
    if (!raw) return EMPTY_REGIONS
    const parsed = JSON.parse(raw) as Partial<StoredRegions>
    return { residence: cleanIds(parsed.residence), work: cleanIds(parsed.work) }
  } catch {
    return EMPTY_REGIONS
  }
}

export function saveRegions(regions: StoredRegions, storage: Storage = localStorage): void {
  try {
    storage.setItem(REGION_STORAGE_KEY, JSON.stringify(regions))
  } catch {
    // storage full or blocked: the choice still works for this visit
  }
}

export function hasRegions(regions: StoredRegions): boolean {
  return regions.residence.length > 0 || regions.work.length > 0
}
