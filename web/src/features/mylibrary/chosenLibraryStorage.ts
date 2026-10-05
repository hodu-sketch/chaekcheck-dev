// Libraries added by hand before login live only in this browser (cycle 07, ADR-0057, AC-18). Never sent to the
// server for storage: only with each search as `libraryIds`, and once to the account when the user agrees (S11).

/** [안 이름] key; the version suffix lets a later format change ignore old data. */
export const CHOSEN_LIBRARIES_KEY = 'chaekcheck.chosenLibraries.v1'

/** Same limit as an account (spec R3) [확정]. */
export const MAX_CHOSEN_LIBRARIES = 20

function cleanIds(value: unknown): number[] {
  if (!Array.isArray(value)) return []
  const ids = value.filter((id): id is number => Number.isSafeInteger(id) && id > 0)
  return [...new Set(ids)].slice(0, MAX_CHOSEN_LIBRARIES)
}

/** The saved ids, oldest first; empty when nothing is saved, the value is broken or storage is blocked. */
export function loadChosenLibraries(storage: Storage | undefined = globalThis.localStorage): number[] {
  try {
    const raw = storage?.getItem(CHOSEN_LIBRARIES_KEY)
    return raw ? cleanIds(JSON.parse(raw)) : []
  } catch {
    return []
  }
}

/** @returns false when the browser would not store it (blocked or full): the screen says so */
export function saveChosenLibraries(ids: number[], storage: Storage | undefined = globalThis.localStorage): boolean {
  try {
    if (!storage) return false
    storage.setItem(CHOSEN_LIBRARIES_KEY, JSON.stringify(ids))
    return true
  } catch {
    return false
  }
}
