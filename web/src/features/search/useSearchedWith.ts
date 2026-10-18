import { useCallback, useState } from 'react'
import { useRegions, type RegionSource } from '../region/RegionContext'

/** The regions and added libraries a search was started with (cycle 22 step 1-4, D6). */
export interface SearchedWith {
  residence: number[]
  work: number[]
  libraryIds: number[]
}

interface Snapshot {
  value: SearchedWith
  /** Where the regions came from when it was taken: a login or logout starts from the new source's values. */
  source: RegionSource
  /** Taken while the account could not be read: taken again once it has been. */
  accountFailed: boolean
}

/**
 * "검색 당시 값" (cycle 22 step 1-4, D6): the search screen searches with the regions and added libraries as they were
 * when the search was started, so checking a region only saves it and never searches again by itself.
 *
 * The snapshot is taken
 * <ul>
 * <li>when the regions first turn `ready` (the page is opened; also after a login or logout, and once a failed
 * account read succeeds),</li>
 * <li>by {@link UseSearchedWith.retake}: the 검색 button, the buttons inside the results (step 4), and after an
 * `invalid_region` answer whose re-check changed the regions.</li>
 * </ul>
 * Back and forward keep the snapshot: an earlier address searches again with the values of the last snapshot. While
 * the regions are not ready, `searchedWith` is null (or, for a retake, the old value until they are).
 */
export interface UseSearchedWith {
  searchedWith: SearchedWith | null
  /** Takes the snapshot again from the current values, as soon as they are ready. */
  retake: () => void
  /** Re-checks the stored region ids (`invalid_region`); when they changed, the snapshot follows them. */
  resolveAndRetake: () => Promise<boolean>
}

export function useSearchedWith(): UseSearchedWith {
  const { regions, libraryIds, source, ready, accountFailed, resolveStoredRegions } = useRegions()
  const [snapshot, setSnapshot] = useState<Snapshot | null>(null)
  const [pending, setPending] = useState(false)

  // taken while rendering (React's "adjusting state when a prop changes"), so no render searches with an old value
  if (ready && (pending || snapshot === null || snapshot.source !== source || (snapshot.accountFailed && !accountFailed))) {
    setSnapshot({ value: { residence: regions.residence, work: regions.work, libraryIds }, source, accountFailed })
    setPending(false)
  }

  const retake = useCallback(() => setPending(true), [])
  const resolveAndRetake = useCallback(async () => {
    const changed = await resolveStoredRegions()
    if (changed) setPending(true)
    return changed
  }, [resolveStoredRegions])

  return { searchedWith: snapshot?.value ?? null, retake, resolveAndRetake }
}
