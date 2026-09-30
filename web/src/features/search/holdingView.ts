import type { Eligibility, ResultFilter } from '../../api/types'
import type { HoldingsState } from './holdingPlan'
import type { BatchView } from './ResultList'
import { applyFilter, sortBatch } from './sortBatch'

/**
 * Holding batches sorted with the cycle 02·03 rules [확정]: one row = one book at one library; inside a book,
 * joinable libraries first, in plan order (ADR-0027). Detail pages carry no loan rate, so that tie-breaker is empty.
 */
export function holdingBatchViews(state: HoldingsState, filter: ResultFilter): BatchView[] {
  const libraries = state.libraries
  const context = {
    planIndex: new Map(libraries.map((l, index) => [l.id, index])),
    eligibility: new Map<number, Eligibility>(libraries.map((l) => [l.id, l.eligibility])),
    cautioned: new Set(libraries.filter((l) => l.joinCaution).map((l) => l.id)),
    visitRequired: new Set(libraries.filter((l) => l.visitRequired).map((l) => l.id)),
    loanRates: new Map<number, number | null>(),
  }
  const answered = new Set<number>()
  return state.batches.map((batch) => {
    for (const id of batch.requestedLibraries) answered.add(id)
    return { batch, sorted: applyFilter(sortBatch(batch.rows, context), filter), answeredSoFar: answered.size }
  })
}

/** "9월 28일~30일 기준" from two YYYY-MM-DD dates; null when unknown [안]. */
export function collectedRange(from: string | null, to: string | null): string | null {
  if (!from || !to) return null
  const [, fm, fd] = from.split('-').map(Number)
  const [, tm, td] = to.split('-').map(Number)
  if (from === to) return `${fm}월 ${fd}일 기준`
  return fm === tm ? `${fm}월 ${fd}일~${td}일 기준` : `${fm}월 ${fd}일~${tm}월 ${td}일 기준`
}
