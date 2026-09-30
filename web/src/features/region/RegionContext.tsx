import { createContext, use, useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { fetchResolutions, MAX_RESOLUTION_IDS } from '../../api/client'
import type { RegionResolution } from '../../api/types'
import { applyResolutions, type RegionNotice } from './applyResolutions'
import { hasRegions, loadRegions, saveRegions, type StoredRegions } from './regionStorage'

interface RegionContextValue {
  regions: StoredRegions
  /** False while stored ids are being checked at startup; searches wait for it so they do not hit a 422. */
  ready: boolean
  notices: RegionNotice[]
  setRegions: (regions: StoredRegions) => void
  /** Re-checks stored ids (after an invalid_region answer). Resolves true when something changed. */
  resolveStoredRegions: () => Promise<boolean>
}

const RegionContext = createContext<RegionContextValue | null>(null)

async function resolveAll(ids: number[]): Promise<RegionResolution[]> {
  const chunks: number[][] = []
  for (let i = 0; i < ids.length; i += MAX_RESOLUTION_IDS) chunks.push(ids.slice(i, i + MAX_RESOLUTION_IDS))
  const results = await Promise.all(chunks.map((chunk) => fetchResolutions(chunk)))
  return results.flat()
}

export function RegionProvider({ children }: { children: ReactNode }) {
  const [regions, setRegionsState] = useState<StoredRegions>(() => loadRegions())
  const [ready, setReady] = useState(() => !hasRegions(loadRegions()))
  const [notices, setNotices] = useState<RegionNotice[]>([])
  const regionsRef = useRef(regions)
  useEffect(() => {
    regionsRef.current = regions
  }, [regions])

  const setRegions = useCallback((next: StoredRegions) => {
    saveRegions(next)
    setRegionsState(next)
    setNotices([])
  }, [])

  const resolveStoredRegions = useCallback(async () => {
    const current = regionsRef.current
    const ids = [...new Set([...current.residence, ...current.work])]
    if (ids.length === 0) return false
    const outcome = applyResolutions(current, await resolveAll(ids))
    if (outcome.changed) {
      saveRegions(outcome.regions)
      setRegionsState(outcome.regions)
    }
    setNotices(outcome.notices)
    return outcome.changed
  }, [])

  // Check stored ids once when the app opens (spec 5.8). On failure keep them; the search reports its own errors.
  useEffect(() => {
    if (ready) return
    let cancelled = false
    resolveStoredRegions()
      .catch(() => false)
      .finally(() => {
        if (!cancelled) setReady(true)
      })
    return () => {
      cancelled = true
    }
  }, [ready, resolveStoredRegions])

  const value = useMemo(
    () => ({ regions, ready, notices, setRegions, resolveStoredRegions }),
    [regions, ready, notices, setRegions, resolveStoredRegions],
  )
  return <RegionContext value={value}>{children}</RegionContext>
}

export function useRegions(): RegionContextValue {
  const value = use(RegionContext)
  if (!value) throw new Error('useRegions must be used inside RegionProvider')
  return value
}
