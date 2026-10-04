import { createContext, use, useCallback, useEffect, useMemo, useState, type ReactNode } from 'react'
import { getMyLibraries, getMyRegions, putMyRegions } from '../../api/myLibraries'
import type { MyRegions } from '../../api/types'
import { useOptionalAuth } from '../auth/AuthContext'

/**
 * The signed-in account's saved regions and added libraries (cycle 06, handoff step 9). Both are read at once when the
 * browser becomes signed in, and forgotten when it signs out. `inactive` covers checking, signed out and login off:
 * the screen then works as in cycle 05.
 */
export type MyLibraryStatus = 'inactive' | 'loading' | 'error' | 'ready'

interface MyLibraryValue {
  status: MyLibraryStatus
  /** The signed-in account, when there is one. */
  accountId: number | null
  /** Saved region ids as the server has them (abolished ones included); null until read. */
  regions: MyRegions | null
  /** Added libraries, disabled ones included, oldest first. */
  libraryIds: number[]
  maxLibraries: number
  /** Reads both again after a failure. */
  retry: () => void
  /** `PUT /api/v1/me/regions`; the saved value becomes the state. Throws ApiError. */
  saveRegions: (regions: MyRegions) => Promise<MyRegions>
  /** Replaces the added libraries shown (the 내 도서관 screen saves each change itself). */
  setLibraryIds: (update: (ids: number[]) => number[]) => void
}

/** [확정] spec R3; the server sends the same number as `max`. */
const DEFAULT_MAX = 20

const MyLibraryContext = createContext<MyLibraryValue | null>(null)

interface Loaded {
  accountId: number
  status: 'loading' | 'error' | 'ready'
  regions: MyRegions | null
  libraryIds: number[]
  max: number
}

export function MyLibraryProvider({ children }: { children: ReactNode }) {
  const auth = useOptionalAuth()
  const accountId = auth?.state.status === 'signed-in' ? auth.state.me.id : null
  const [loaded, setLoaded] = useState<Loaded | null>(null)
  const [attempt, setAttempt] = useState(0)

  useEffect(() => {
    if (accountId === null) {
      setLoaded(null)
      return
    }
    let cancelled = false
    setLoaded({ accountId, status: 'loading', regions: null, libraryIds: [], max: DEFAULT_MAX })
    Promise.all([getMyRegions(), getMyLibraries()])
      .then(([regions, libraries]) => {
        if (!cancelled) {
          setLoaded({ accountId, status: 'ready', regions, libraryIds: libraries.libraryIds, max: libraries.max })
        }
      })
      .catch(() => {
        if (!cancelled) setLoaded({ accountId, status: 'error', regions: null, libraryIds: [], max: DEFAULT_MAX })
      })
    return () => {
      cancelled = true
    }
  }, [accountId, attempt])

  const retry = useCallback(() => setAttempt((n) => n + 1), [])

  const saveRegions = useCallback(async (regions: MyRegions) => {
    const saved = await putMyRegions(regions)
    setLoaded((current) => (current ? { ...current, status: 'ready', regions: saved } : current))
    return saved
  }, [])

  const setLibraryIds = useCallback((update: (ids: number[]) => number[]) => {
    setLoaded((current) => (current ? { ...current, libraryIds: update(current.libraryIds) } : current))
  }, [])

  // an answer for another account (signed out and in again meanwhile) is not shown
  const current = loaded !== null && loaded.accountId === accountId ? loaded : null
  const value = useMemo<MyLibraryValue>(
    () => ({
      status: current?.status ?? 'inactive',
      accountId,
      regions: current?.regions ?? null,
      libraryIds: current?.libraryIds ?? [],
      maxLibraries: current?.max ?? DEFAULT_MAX,
      retry,
      saveRegions,
      setLibraryIds,
    }),
    [current, accountId, retry, saveRegions, setLibraryIds],
  )
  return <MyLibraryContext value={value}>{children}</MyLibraryContext>
}

export function useMyLibrary(): MyLibraryValue {
  const value = use(MyLibraryContext)
  if (!value) throw new Error('useMyLibrary must be used inside MyLibraryProvider')
  return value
}

/** For screens also used without the provider (tests of the search screen): `null` there. */
export function useOptionalMyLibrary(): MyLibraryValue | null {
  return use(MyLibraryContext)
}
