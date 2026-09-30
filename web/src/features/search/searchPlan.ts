import type { LibraryPage } from '../../api/types'

/**
 * Where each library of the plan stands in this search (design-delta "도서관 하나의 조회 상태"). The browser keeps
 * it; the server keeps no search state (ADR-0014).
 */
export type EntryStatus = 'NotRequested' | 'Requested' | 'HasMore' | 'Exhausted' | 'Failed'

export interface PlanEntry {
  status: EntryStatus
  /** Page to ask for next (1 before the first request). */
  nextPage: number
}

export interface PlanState {
  /** Library ids in plan order (from `targets`). */
  order: number[]
  entries: Record<number, PlanEntry>
}

export interface CandidateRef {
  libraryId: number
  page: number
}

export function initialPlan(order: number[]): PlanState {
  const entries: Record<number, PlanEntry> = {}
  for (const id of order) entries[id] = { status: 'NotRequested', nextPage: 1 }
  return { order, entries }
}

/**
 * Candidates for the next batch, at most `max` (K):
 * - first round: libraries not requested yet, in plan order, page 1
 * - later rounds: libraries with more pages, in plan order, their next page (round robin)
 * Failed libraries never come back [확정]. Candidates the server did not get to stay NotRequested.
 */
export function nextCandidates(plan: PlanState, max: number): CandidateRef[] {
  const notRequested = plan.order.filter((id) => plan.entries[id]?.status === 'NotRequested')
  const pool = notRequested.length > 0 ? notRequested : plan.order.filter((id) => plan.entries[id]?.status === 'HasMore')
  return pool.slice(0, max).map((libraryId) => ({ libraryId, page: plan.entries[libraryId].nextPage }))
}

/** Libraries that still have something to fetch (shown as "남은 도서관 N곳"). */
export function remainingLibraries(plan: PlanState): number {
  return plan.order.filter((id) => {
    const status = plan.entries[id]?.status
    return status === 'NotRequested' || status === 'HasMore'
  }).length
}

export function markRequested(plan: PlanState, libraryId: number): PlanState {
  return update(plan, libraryId, (entry) => ({ ...entry, status: 'Requested' }))
}

/**
 * A page arrived (from a batch or from the per-library "more" API): the next page is the one after it, so round
 * robin never asks for the same page twice.
 */
export function markPage(plan: PlanState, page: LibraryPage): PlanState {
  return update(plan, page.libraryId, (entry) => {
    const next = Math.max(entry.nextPage, page.page + 1)
    return { status: page.hasMore ? 'HasMore' : 'Exhausted', nextPage: next }
  })
}

export function markFailed(plan: PlanState, libraryId: number): PlanState {
  return update(plan, libraryId, (entry) => ({ ...entry, status: 'Failed' }))
}

function update(plan: PlanState, libraryId: number, change: (entry: PlanEntry) => PlanEntry): PlanState {
  const entry = plan.entries[libraryId]
  return entry ? { ...plan, entries: { ...plan.entries, [libraryId]: change(entry) } } : plan
}
