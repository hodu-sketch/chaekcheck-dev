import { useQuery } from '@tanstack/react-query'
import { createContext, useContext, useEffect, useMemo, useState, type ReactNode } from 'react'
import {
  fetchLookupStatus,
  LOOKUP_STATUS_POLL_SECONDS,
  LOOKUP_STATUS_STALE_MS,
  type LookupStatus,
} from '../../api/lookupStatus'
import type { LookupView } from './lookupStatusText'
import { subscribeServerBusy } from './serverBusy'

export interface LookupStatusValue {
  /** Null before the first answer: the header draws nothing but the place (handoff 1-12 [확정]). */
  view: LookupView | null
  /** Libraries whose host has a queue (spec R4), from the latest answer; empty while there is none. */
  queued: ReadonlySet<number>
}

const EMPTY: LookupStatusValue = { view: null, queued: new Set() }

const LookupStatusContext = createContext<LookupStatusValue>(EMPTY)

/** The header light's state; outside the provider (tests, screens on their own) it is empty. */
export function useLookupStatus(): LookupStatusValue {
  return useContext(LookupStatusContext)
}

export const LOOKUP_STATUS_QUERY_KEY = ['lookup-status'] as const

/**
 * Asks `/api/v1/lookup-status` every 10 seconds (spec R1, handoff 1-12). TanStack Query v5 runs the interval only
 * while the tab is visible (`focusManager`, `visibilitychange`; `refetchIntervalInBackground` stays off) and
 * `refetchOnWindowFocus: 'always'` asks once more the moment the tab is visible again (AC-6). The judgment itself
 * is here: 30 seconds after the last success the light is gray 「상태 모름」 (R5, AC-42), also judged at once on the
 * tab's return; a 503 `server_busy` from a search makes it red until the next answer (R2, AC-3); gray beats every
 * other level [확정].
 */
export function LookupStatusProvider({ children }: { children: ReactNode }) {
  const query = useQuery({
    queryKey: LOOKUP_STATUS_QUERY_KEY,
    queryFn: ({ signal }) => fetchLookupStatus(signal),
    refetchInterval: LOOKUP_STATUS_POLL_SECONDS * 1000,
    refetchIntervalInBackground: false,
    refetchOnWindowFocus: 'always',
    // a failed ask is a failed ask: the 30-second rule decides, not a retry (R5)
    retry: false,
    staleTime: 0,
  })
  const { data, dataUpdatedAt, errorUpdatedAt } = query
  // when the provider mounted: the 30 seconds count from here until the first success
  const [mountedAt] = useState(() => Date.now())
  // re-judged: at the 30-second mark, on the tab's return and on a 503 report
  const [, setTick] = useState(0)
  const [busyAt, setBusyAt] = useState<number | null>(null)

  const lastSuccessAt = dataUpdatedAt || mountedAt
  const staleAt = lastSuccessAt + LOOKUP_STATUS_STALE_MS
  const now = Date.now()
  const stale = now >= staleAt

  useEffect(() => {
    if (stale) return
    const timer = window.setTimeout(() => setTick((t) => t + 1), staleAt - Date.now())
    return () => window.clearTimeout(timer)
  }, [staleAt, stale])

  useEffect(() => {
    const onVisible = () => {
      if (document.visibilityState === 'visible') setTick((t) => t + 1)
    }
    document.addEventListener('visibilitychange', onVisible)
    return () => document.removeEventListener('visibilitychange', onVisible)
  }, [])

  useEffect(() => subscribeServerBusy(() => setBusyAt(Date.now())), [])

  const value = useMemo<LookupStatusValue>(() => {
    // a status answer (success or failure) after the 503 replaces the red; `>=` because both can land in one ms
    const busy = busyAt !== null && busyAt >= Math.max(dataUpdatedAt, errorUpdatedAt)
    const view = judge(data, stale, busy)
    const queued = view?.level === 'GRAY' || !data ? new Set<number>() : new Set(data.queuedLibraryIds)
    return { view, queued }
  }, [data, dataUpdatedAt, errorUpdatedAt, stale, busyAt])

  return <LookupStatusContext.Provider value={value}>{children}</LookupStatusContext.Provider>
}

function judge(data: LookupStatus | undefined, stale: boolean, busy: boolean): LookupView | null {
  if (stale) return { level: 'GRAY', source: 'stale' }
  if (!data) return null
  if (data.level === 'GRAY') return { level: 'GRAY', source: 'server', reason: data.reason }
  if (busy) return { level: 'RED', source: 'busy' }
  return { level: data.level, source: 'server', reason: data.reason }
}
