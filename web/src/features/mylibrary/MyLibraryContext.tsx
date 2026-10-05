import { createContext, use, useCallback, useEffect, useMemo, useState, type ReactNode } from 'react'
import { addMyLibrary, getMyLibraries, getMyRegions, putMyRegions, removeMyLibrary } from '../../api/myLibraries'
import type { MyRegions } from '../../api/types'
import { useOptionalAuth } from '../auth/AuthContext'
import { loadChosenLibraries, MAX_CHOSEN_LIBRARIES, saveChosenLibraries } from './chosenLibraryStorage'
import { BrowserStorageError, LibraryLimitError } from './myLibraryErrors'

/**
 * The libraries added by hand and, while signed in, the account's saved regions (cycle 06, handoff step 9).
 * - `account`: signed in. Both are read at once from the server and saved there (cycle 06).
 * - `browser`: signed out or login off (cycle 07, ADR-0057). The added libraries live in this browser only; the
 *   regions come from the region context (also this browser). Read again whenever the browser becomes signed out.
 * - `waiting`: the login check has not answered yet.
 */
export type MyLibraryMode = 'waiting' | 'browser' | 'account'

/** `inactive` while waiting; `ready` in the browser at once; loading, error or ready for the account. */
export type MyLibraryStatus = 'inactive' | 'loading' | 'error' | 'ready'

interface MyLibraryValue {
  mode: MyLibraryMode
  status: MyLibraryStatus
  /** The signed-in account, when there is one. */
  accountId: number | null
  /** Saved region ids as the server has them (abolished ones included); null until read, and in the browser. */
  regions: MyRegions | null
  /** Added libraries, disabled ones included, oldest first. */
  libraryIds: number[]
  maxLibraries: number
  /** Reads both again after a failure. */
  retry: () => void
  /** `PUT /api/v1/me/regions`; the saved value becomes the state. Throws ApiError. */
  saveRegions: (regions: MyRegions) => Promise<MyRegions>
  /**
   * Adds one library, shown at once and undone on failure. Throws {@link LibraryLimitError} at the limit,
   * {@link BrowserStorageError} when the browser would not store it, an ApiError from the server.
   */
  addLibrary: (libraryId: number) => Promise<void>
  /** Removes one library, undone on failure (same errors). */
  removeLibrary: (libraryId: number) => Promise<void>
  /** Replaces the account's added libraries shown (after S11 moved the browser's to the account). */
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
  const authStatus = auth ? auth.state.status : 'off'
  const accountId = auth?.state.status === 'signed-in' ? auth.state.me.id : null
  const mode: MyLibraryMode = authStatus === 'checking' ? 'waiting' : accountId !== null ? 'account' : 'browser'
  const [loaded, setLoaded] = useState<Loaded | null>(null)
  const [attempt, setAttempt] = useState(0)
  const [browser, setBrowser] = useState<{ mode: MyLibraryMode; ids: number[] }>(() => ({
    mode,
    ids: loadChosenLibraries(),
  }))
  // back to the browser (signed out): its own list again, as another tab may have changed it [안]
  if (browser.mode !== mode) {
    setBrowser({ mode, ids: mode === 'browser' ? loadChosenLibraries() : browser.ids })
  }
  const browserIds = browser.ids
  const setBrowserIds = useCallback((ids: number[]) => setBrowser((current) => ({ ...current, ids })), [])

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
  const accountIds = current?.libraryIds
  const libraryIds = useMemo(
    () => (mode === 'account' ? (accountIds ?? []) : mode === 'browser' ? browserIds : []),
    [mode, accountIds, browserIds],
  )
  const maxLibraries = mode === 'account' ? (current?.max ?? DEFAULT_MAX) : MAX_CHOSEN_LIBRARIES

  const addLibrary = useCallback(
    async (libraryId: number) => {
      if (libraryIds.includes(libraryId)) return
      if (libraryIds.length >= maxLibraries) throw new LibraryLimitError()
      if (mode === 'browser') {
        const next = [...libraryIds, libraryId]
        if (!saveChosenLibraries(next)) throw new BrowserStorageError()
        setBrowserIds(next)
        return
      }
      setLibraryIds((ids) => (ids.includes(libraryId) ? ids : [...ids, libraryId]))
      try {
        await addMyLibrary(libraryId)
      } catch (error) {
        setLibraryIds((ids) => ids.filter((id) => id !== libraryId))
        throw error
      }
    },
    [mode, libraryIds, maxLibraries, setLibraryIds, setBrowserIds],
  )

  const removeLibrary = useCallback(
    async (libraryId: number) => {
      if (mode === 'browser') {
        const next = libraryIds.filter((id) => id !== libraryId)
        if (!saveChosenLibraries(next)) throw new BrowserStorageError()
        setBrowserIds(next)
        return
      }
      const before = libraryIds
      setLibraryIds((ids) => ids.filter((id) => id !== libraryId))
      try {
        await removeMyLibrary(libraryId)
      } catch (error) {
        setLibraryIds(() => before)
        throw error
      }
    },
    [mode, libraryIds, setLibraryIds, setBrowserIds],
  )

  const status: MyLibraryStatus = mode === 'account' ? (current?.status ?? 'loading') : mode === 'browser' ? 'ready' : 'inactive'
  const value = useMemo<MyLibraryValue>(
    () => ({
      mode,
      status,
      accountId,
      regions: current?.regions ?? null,
      libraryIds,
      maxLibraries,
      retry,
      saveRegions,
      addLibrary,
      removeLibrary,
      setLibraryIds,
    }),
    [mode, status, current, accountId, libraryIds, maxLibraries, retry, saveRegions, addLibrary, removeLibrary, setLibraryIds],
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
