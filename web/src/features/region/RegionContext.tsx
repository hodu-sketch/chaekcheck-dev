import { createContext, use, useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { useOptionalAuth } from '../auth/AuthContext'
import { useOptionalMyLibrary } from '../mylibrary/MyLibraryContext'
import type { RegionNotice } from './applyResolutions'
import { EMPTY_REGIONS, hasRegions, loadRegions, saveRegions, type StoredRegions } from './regionStorage'
import { resolveRegions, toStored } from './resolveRegions'

/**
 * Where the regions come from (cycle 06 C18): `browser` before login (and when login is off), `account` while signed
 * in (the server's saved values only; this browser's are neither read nor written), `waiting` while the login check
 * has not answered yet.
 */
export type RegionSource = 'waiting' | 'browser' | 'account'

interface RegionContextValue {
  regions: StoredRegions
  source: RegionSource
  /** False while ids are being checked (at startup, after reading the server's); searches wait so they do not hit a 422. */
  ready: boolean
  notices: RegionNotice[]
  /**
   * Saves the regions: in this browser before login, with `PUT /api/v1/me/regions` while signed in (rejects with the
   * ApiError; the regions stay as they were).
   */
  setRegions: (regions: StoredRegions) => Promise<void>
  /** Re-checks the current ids (after an invalid_region answer). Resolves true when something changed. */
  resolveStoredRegions: () => Promise<boolean>
}

const RegionContext = createContext<RegionContextValue | null>(null)

function regionsKey(regions: StoredRegions): string {
  return `${regions.residence.join(',')}|${regions.work.join(',')}`
}

export function RegionProvider({ children }: { children: ReactNode }) {
  const auth = useOptionalAuth()
  const myLibrary = useOptionalMyLibrary()
  const authStatus = auth ? auth.state.status : 'signed-out'
  const source: RegionSource =
    authStatus === 'checking' ? 'waiting' : authStatus === 'signed-in' && myLibrary ? 'account' : 'browser'
  const serverRegions = source === 'account' && myLibrary?.status === 'ready' ? myLibrary.regions : null
  const serverKey = serverRegions ? regionsKey(toStored(serverRegions)) : null

  const [regions, setRegionsState] = useState<StoredRegions>(EMPTY_REGIONS)
  const [ready, setReady] = useState(false)
  const [notices, setNotices] = useState<RegionNotice[]>([])
  const regionsRef = useRef(regions)
  useEffect(() => {
    regionsRef.current = regions
  }, [regions])
  /** The server value this browser has just saved: already checked, so not resolved again. */
  const savedKey = useRef<string | null>(null)

  // Load from the current source and check the ids once (spec 5.8, R6). The server's values are resolved for the
  // screen and the notices but never saved back (C19); only S2 and S11 save them.
  const loadKey = `${source}:${serverKey ?? ''}`
  useEffect(() => {
    if (source !== 'account') savedKey.current = null
    if (source === 'waiting') {
      setRegionsState(EMPTY_REGIONS)
      setNotices([])
      setReady(false)
      return
    }
    if (source === 'account' && serverRegions === null) {
      // reading the account's values: search without regions meanwhile, do not block it [확정] (flow S1)
      setRegionsState(EMPTY_REGIONS)
      setNotices([])
      setReady(true)
      return
    }
    if (source === 'account' && savedKey.current === serverKey) return
    const initial = source === 'browser' ? loadRegions() : toStored(serverRegions!)
    setRegionsState(initial)
    setNotices([])
    if (!hasRegions(initial)) {
      setReady(true)
      return
    }
    setReady(false)
    let cancelled = false
    resolveRegions(initial)
      .then((outcome) => {
        if (cancelled) return
        if (outcome.changed) {
          if (source === 'browser') saveRegions(outcome.regions)
          setRegionsState(outcome.regions)
        }
        setNotices(outcome.notices)
      })
      // on failure keep them; the search reports its own errors
      .catch(() => undefined)
      .finally(() => {
        if (!cancelled) setReady(true)
      })
    return () => {
      cancelled = true
    }
    // loadKey stands for source and the server's value
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [loadKey])

  const saveToAccount = myLibrary?.saveRegions
  const setRegions = useCallback(
    async (next: StoredRegions) => {
      if (source === 'account') {
        if (!saveToAccount) return
        const saved = toStored(await saveToAccount({ residenceRegionIds: next.residence, workRegionIds: next.work }))
        savedKey.current = regionsKey(saved)
        setRegionsState(saved)
        setReady(true)
      } else {
        if (source === 'browser') saveRegions(next)
        setRegionsState(next)
      }
      setNotices([])
    },
    [source, saveToAccount],
  )

  const resolveStoredRegions = useCallback(async () => {
    const outcome = await resolveRegions(regionsRef.current)
    if (outcome.changed) {
      if (source === 'browser') saveRegions(outcome.regions)
      setRegionsState(outcome.regions)
    }
    setNotices(outcome.notices)
    return outcome.changed
  }, [source])

  const value = useMemo(
    () => ({ regions, source, ready, notices, setRegions, resolveStoredRegions }),
    [regions, source, ready, notices, setRegions, resolveStoredRegions],
  )
  return <RegionContext value={value}>{children}</RegionContext>
}

export function useRegions(): RegionContextValue {
  const value = use(RegionContext)
  if (!value) throw new Error('useRegions must be used inside RegionProvider')
  return value
}
