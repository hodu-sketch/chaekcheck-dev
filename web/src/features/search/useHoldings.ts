import { useCallback, useEffect, useReducer, useRef } from 'react'
import { holdingBatchStream, holdingStream } from '../../api/searchStream'
import type { BookItem, LibraryScope, ResultFilter, VisitFilter } from '../../api/types'
import { reportBlocked } from './blocked'
import {
  EMPTY_HOLDINGS,
  holdingsReducer,
  nextHoldingCandidates,
  remainingHoldings,
} from './holdingPlan'
import { MAX_CANDIDATES } from './useSearch'

export interface HoldingPlanOptions {
  filter: ResultFilter
  scope: LibraryScope
  visit: VisitFilter
  residenceRegionIds: number[]
  workRegionIds: number[]
  libraryIds: number[]
  /** Cycle 10 (S14): ask only the first N plan holdings (`holdingLimit`); left out = all. */
  holdingLimit?: number
}

/**
 * How many holding batches run by themselves, the first one included, before the screen shows "더 보기" instead
 * (cycle 20 spec R15-1, 전반 점검 #7 [확정 3]; `VITE_HOLDING_AUTO_BATCHES`). "더 보기" starts a new run of the same size.
 * The S14 window's widening (`holdingLimit` 3 → 6 → 10 → 20) is a new `open` each time, so it counts apart [확정].
 */
export const HOLDING_AUTO_BATCHES = positive(import.meta.env.VITE_HOLDING_AUTO_BATCHES, 3)

function positive(raw: unknown, fallback: number): number {
  const value = Number(raw)
  return Number.isFinite(value) && value >= 1 ? Math.floor(value) : fallback
}

/**
 * Live availability of books from their holdings' detail pages (ADR-0030): `open` asks the holdings of some books
 * (first stream: `targets` + batch), `loadNext` asks the holdings still not asked ("더 보기"). The server keeps no
 * state; only the browser knows which holdings were asked. Leaving the page aborts the open stream. When a batch ends
 * with holdings still to ask, the next batch starts by itself, up to HOLDING_AUTO_BATCHES per run (cycle 20).
 */
export function useHoldings() {
  const [state, dispatch] = useReducer(holdingsReducer, EMPTY_HOLDINGS)
  const controller = useRef<AbortController | null>(null)
  const books = useRef(new Map<number, BookItem>())
  const batchCounter = useRef(0)
  const filterRef = useRef<ResultFilter>('all')
  // batches of the current run (an `open` or a "더 보기" starts one), and the batch whose end was already continued
  const runBatches = useRef(0)
  const continuedAfter = useRef(0)
  useEffect(() => () => controller.current?.abort(), [])

  const reset = useCallback(() => {
    controller.current?.abort()
    books.current = new Map()
    batchCounter.current = 0
    runBatches.current = 0
    dispatch({ type: 'reset' })
  }, [])

  /**
   * First stream for these books (at most 20): their plan holdings are added to the plan and batch N starts. Next
   * batches use the same filter; scope, visit and regions only shape this plan.
   */
  const open = useCallback((items: BookItem[], options: HoldingPlanOptions) => {
    controller.current?.abort()
    const next = new AbortController()
    controller.current = next
    for (const book of items) books.current.set(book.id, book)
    const batchNo = ++batchCounter.current
    runBatches.current = 1
    dispatch({ type: 'batch-start', batchNo })
    const { filter, scope, visit, residenceRegionIds, workRegionIds, libraryIds, holdingLimit } = options
    filterRef.current = filter
    holdingStream(
      {
        bookIds: items.map((b) => b.id),
        filter,
        scope,
        visit,
        residenceRegionIds,
        workRegionIds,
        libraryIds,
        ...(holdingLimit !== undefined && { holdingLimit }),
      },
      (event) => dispatch({ type: 'event', batchNo, event, books: books.current }),
      next.signal,
    ).catch((error: unknown) => {
      if (!next.signal.aborted) dispatch({ type: 'batch-failed', batchNo, blocked: reportBlocked(error) })
    })
  }, [])

  const startBatch = useCallback((candidates: number[]) => {
    controller.current?.abort()
    const next = new AbortController()
    controller.current = next
    const batchNo = ++batchCounter.current
    dispatch({ type: 'batch-start', batchNo })
    holdingBatchStream(
      { filter: filterRef.current, batchNo, candidates },
      (event) => dispatch({ type: 'event', batchNo, event, books: books.current }),
      next.signal,
    ).catch((error: unknown) => {
      if (!next.signal.aborted) dispatch({ type: 'batch-failed', batchNo, blocked: reportBlocked(error) })
    })
  }, [])

  /** "더 보기": the holdings still not asked, in order, at most K, as a new run. False when there is none. */
  const loadNext = useCallback((): boolean => {
    if (state.phase === 'streaming') return true
    const candidates = nextHoldingCandidates(state, MAX_CANDIDATES)
    if (candidates.length === 0) return false
    runBatches.current = 1
    startBatch(candidates)
    return true
  }, [state, startBatch])

  // a batch ended by itself with holdings left and the run not full: the next batch starts at once (cycle 20 R15-1,
  // AC-36); a batch that broke off or was refused does not go on (the screen shows "더 보기" and the reason), and
  // nothing left means nothing to continue (AC-28)
  useEffect(() => {
    if (state.phase !== 'done' || state.batchFailed) return
    const last = state.batches.at(-1)
    if (!last || continuedAfter.current === last.batchNo) return
    if (runBatches.current >= HOLDING_AUTO_BATCHES) return
    const candidates = nextHoldingCandidates(state, MAX_CANDIDATES)
    if (candidates.length === 0) return
    continuedAfter.current = last.batchNo
    runBatches.current += 1
    startBatch(candidates)
  }, [state, startBatch])

  return { state, remaining: remainingHoldings(state), open, loadNext, reset }
}
