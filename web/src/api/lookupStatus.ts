import { readProblem } from './client'

/**
 * `GET /api/v1/lookup-status` (cycle 20, spec R1~R4, ADR-0118): how busy the library lookups are right now. Public,
 * no login, `Cache-Control: no-cache`; the server answers from memory, so it still answers 200 while the DB is down
 * (then `level` is GRAY with `reason` DB_ERROR, plan 20 전반 점검 #9).
 */
export type LookupLevel = 'GREEN' | 'YELLOW' | 'RED' | 'GRAY'

/** Why the level is what it is when it is not the slot count alone (spec R2~R4, reviews #21, #24). */
export type LookupReason = 'NONE' | 'DB_ERROR' | 'VENDOR_PAUSED' | 'NO_LANE'

export interface LookupStatus {
  slots: number
  inUse: number
  level: LookupLevel
  reason: LookupReason
  /** Libraries whose host has a queue right now (spec R4); the result rows say "밀려요" for them. */
  queuedLibraryIds: number[]
  serverTime: string
}

/** How often the header asks, in seconds (`VITE_LOOKUP_STATUS_POLL_SECONDS`, handoff 1-12 [확정 10]). */
export const LOOKUP_STATUS_POLL_SECONDS = positive(import.meta.env.VITE_LOOKUP_STATUS_POLL_SECONDS, 10)

/** Last success this long ago: the header says 「상태 모름」 (spec R5) [확정 30s]. */
export const LOOKUP_STATUS_STALE_MS = 30_000

function positive(raw: unknown, fallback: number): number {
  const value = Number(raw)
  return Number.isFinite(value) && value > 0 ? value : fallback
}

export async function fetchLookupStatus(signal?: AbortSignal): Promise<LookupStatus> {
  const response = await fetch('/api/v1/lookup-status', {
    headers: { Accept: 'application/json, application/problem+json' },
    cache: 'no-store',
    signal,
  })
  if (!response.ok) throw await readProblem(response)
  return (await response.json()) as LookupStatus
}
