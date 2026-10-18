import { createContext, use, useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { ApiError } from '../../api/client'
import { sendUsageEvent } from '../../api/usageEvents'
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
  /**
   * Libraries added by hand, sent with every search: the account's after login (cycle 06, AC-6), this browser's
   * before (cycle 07, AC-18).
   */
  libraryIds: number[]
  source: RegionSource
  /**
   * False while ids are being checked (at startup, after reading the server's) and, after login, while the account's
   * 내 도서관 is being read (cycle 22 D7); searches wait so they do not hit a 422 or run without the account's regions.
   */
  ready: boolean
  /**
   * After login, the account's 내 도서관 could not be read (cycle 22 D7): `ready` is true and searches go without
   * regions or added libraries until it is read again.
   */
  accountFailed: boolean
  notices: RegionNotice[]
  /**
   * The last save to the account failed and the regions went back to the last saved value (cycle 22 D6); the screen
   * says {@link REGION_SAVE_FAILED}. Cleared by the next `setRegions`.
   */
  saveFailed: boolean
  /**
   * Saves the regions at once, shown at once (cycle 22 D6, every check of the grid): in this browser before login;
   * while signed in with `PUT /api/v1/me/regions`, one request at a time, and a value set while one is on the way is
   * sent after it (only the latest). A failure puts back the last saved value and sets `saveFailed`; a 422
   * `invalid_region` resolves the ids (`notices`) and saves the resolved ones. The promise settles when no request is
   * left: it rejects with the error after a failure. Never starts a search.
   */
  setRegions: (regions: StoredRegions) => Promise<void>
  /** Re-checks the current ids (after an invalid_region answer). Resolves true when something changed. */
  resolveStoredRegions: () => Promise<boolean>
}

const RegionContext = createContext<RegionContextValue | null>(null)

/** What a failed save says (cycle 06 flow S2, cycle 22 문구표 "저장 실패"). */
export const REGION_SAVE_FAILED = '저장하지 못했어요. 잠시 뒤 다시 해 주세요'

interface Waiter {
  resolve: () => void
  reject: (error: unknown) => void
}

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
  const accountFailed = source === 'account' && myLibrary?.status === 'error'

  const [regions, setRegionsState] = useState<StoredRegions>(EMPTY_REGIONS)
  const [ready, setReady] = useState(false)
  /** The source and server value the state was last loaded from; until it matches, the regions are not ready. */
  const [appliedKey, setAppliedKey] = useState<string | null>(null)
  const [notices, setNotices] = useState<RegionNotice[]>([])
  const [saveFailed, setSaveFailed] = useState(false)
  const regionsRef = useRef(regions)
  useEffect(() => {
    regionsRef.current = regions
  }, [regions])
  /** The server value this browser has just saved: already checked, so not resolved again. */
  const savedKey = useRef<string | null>(null)
  /** The value a failed save goes back to: the one loaded, or the last one saved (cycle 22 D6). */
  const lastSaved = useRef<StoredRegions>(EMPTY_REGIONS)
  /** The account save queue (D6): the value still to send (only the latest), and whether a request is on the way. */
  const pending = useRef<StoredRegions | null>(null)
  const running = useRef(false)
  const waiters = useRef<Waiter[]>([])
  const sourceRef = useRef(source)
  useEffect(() => {
    sourceRef.current = source
  }, [source])

  // Load from the current source and check the ids once (spec 5.8, R6). The server's values are resolved for the
  // screen and the notices but never saved back (C19); only S2 and S11 save them.
  // while the account is read, its status (loading or error) stands in for the server's value
  const loadKey = `${source}:${serverKey ?? (source === 'account' ? (myLibrary?.status ?? '') : '')}`
  useEffect(() => {
    if (source !== 'account') savedKey.current = null
    setAppliedKey(loadKey)
    if (source === 'waiting') {
      lastSaved.current = EMPTY_REGIONS
      setRegionsState(EMPTY_REGIONS)
      setNotices([])
      setReady(false)
      return
    }
    if (source === 'account' && serverRegions === null) {
      // reading the account's values: searches wait for them (cycle 22 D7; before: searched without regions, flow S1).
      // A failed read searches without regions; the screen says so (accountFailed).
      lastSaved.current = EMPTY_REGIONS
      setRegionsState(EMPTY_REGIONS)
      setNotices([])
      setReady(accountFailed)
      return
    }
    // this browser's own saves (one on the way, or the last one): the screen already shows them
    if (source === 'account' && (running.current || savedKey.current === serverKey)) return
    const initial = source === 'browser' ? loadRegions() : toStored(serverRegions!)
    lastSaved.current = initial
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
          lastSaved.current = outcome.regions
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
  const saveToAccountRef = useRef(saveToAccount)
  useEffect(() => {
    saveToAccountRef.current = saveToAccount
  }, [saveToAccount])

  /** Sends the queued value, then the one queued meanwhile, until none is left (D6: never two at once). */
  const drain = useCallback(async () => {
    running.current = true
    let failure: unknown = null
    let failed = false
    while (pending.current) {
      const value = pending.current
      pending.current = null
      const save = saveToAccountRef.current
      try {
        if (!save || sourceRef.current !== 'account') throw new Error('not signed in')
        const saved = toStored(await save({ residenceRegionIds: value.residence, workRegionIds: value.work }))
        savedKey.current = regionsKey(saved)
        lastSaved.current = saved
        reportRegions(saved)
        // the server's form of the value, unless a newer one is already shown
        if (!pending.current) setRegionsState(saved)
      } catch (error) {
        if (sourceRef.current === 'account' && error instanceof ApiError && error.problem.code === 'invalid_region') {
          // regions changed since the tree was read (cycle 06): resolve them, say so, and save the resolved ones
          const outcome = await resolveRegions(value).catch(() => null)
          if (outcome?.changed) {
            setNotices(outcome.notices)
            if (!pending.current) {
              pending.current = outcome.regions
              setRegionsState(outcome.regions)
            }
            continue
          }
        }
        pending.current = null
        failed = true
        failure = error
        if (sourceRef.current === 'account') {
          setRegionsState(lastSaved.current)
          setSaveFailed(true)
        }
      }
    }
    running.current = false
    const settled = waiters.current
    waiters.current = []
    settled.forEach((waiter) => (failed ? waiter.reject(failure) : waiter.resolve()))
  }, [])

  const setRegions = useCallback(
    (next: StoredRegions): Promise<void> => {
      setNotices([])
      setSaveFailed(false)
      if (source === 'waiting') return Promise.resolve()
      if (source === 'browser') {
        saveRegions(next)
        lastSaved.current = next
        setRegionsState(next)
        reportRegions(next)
        return Promise.resolve()
      }
      if (!saveToAccount) return Promise.resolve()
      // shown at once; the queue sends it once the request on the way (if any) is back
      setRegionsState(next)
      pending.current = next
      const done = new Promise<void>((resolve, reject) => waiters.current.push({ resolve, reject }))
      if (!running.current) void drain()
      return done
    },
    [source, saveToAccount, drain],
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

  // the account's after login, this browser's before (cycle 07, ADR-0057): sent with every search either way
  const libraryIds = useMemo(
    () => (source !== 'waiting' && myLibrary?.status === 'ready' ? myLibrary.libraryIds : []),
    [source, myLibrary?.status, myLibrary?.libraryIds],
  )
  // between a new source or server value and the effect that loads it, the old regions must not be searched with
  const current = ready && appliedKey === loadKey
  const value = useMemo(
    () => ({
      regions,
      libraryIds,
      source,
      ready: current,
      // only together with ready, so the two never disagree for a render (the effect sets ready a step later)
      accountFailed: accountFailed && current,
      notices,
      saveFailed,
      setRegions,
      resolveStoredRegions,
    }),
    [regions, libraryIds, source, current, accountFailed, notices, saveFailed, setRegions, resolveStoredRegions],
  )
  return <RegionContext value={value}>{children}</RegionContext>
}

/** Usage report of a saved region choice, before and after login alike (cycle 15). */
function reportRegions(regions: StoredRegions): void {
  sendUsageEvent({ type: 'region_change', residenceRegionIds: regions.residence, workRegionIds: regions.work })
}

export function useRegions(): RegionContextValue {
  const value = use(RegionContext)
  if (!value) throw new Error('useRegions must be used inside RegionProvider')
  return value
}
