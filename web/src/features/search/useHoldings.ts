import { useCallback, useEffect, useReducer, useRef } from 'react'
import { holdingBatchStream, holdingStream } from '../../api/searchStream'
import type { BookItem, LibraryScope, ResultFilter, VisitFilter } from '../../api/types'
import { blockedBy } from './blocked'
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
 * Live availability of books from their holdings' detail pages (ADR-0030): `open` asks the holdings of some books
 * (first stream: `targets` + batch), `loadNext` asks the holdings still not asked ("더 보기"). The server keeps no
 * state; only the browser knows which holdings were asked. Leaving the page aborts the open stream.
 */
export function useHoldings() {
  const [state, dispatch] = useReducer(holdingsReducer, EMPTY_HOLDINGS)
  const controller = useRef<AbortController | null>(null)
  const books = useRef(new Map<number, BookItem>())
  const batchCounter = useRef(0)
  const filterRef = useRef<ResultFilter>('all')
  useEffect(() => () => controller.current?.abort(), [])

  const reset = useCallback(() => {
    controller.current?.abort()
    books.current = new Map()
    batchCounter.current = 0
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
      if (!next.signal.aborted) dispatch({ type: 'batch-failed', batchNo, blocked: blockedBy(error) })
    })
  }, [])

  /** Next batch: holdings still not asked, in order, at most K. False when there is none. */
  const loadNext = useCallback((): boolean => {
    if (state.phase === 'streaming') return true
    const candidates = nextHoldingCandidates(state, MAX_CANDIDATES)
    if (candidates.length === 0) return false
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
      if (!next.signal.aborted) dispatch({ type: 'batch-failed', batchNo, blocked: blockedBy(error) })
    })
    return true
  }, [state])

  return { state, remaining: remainingHoldings(state), open, loadNext, reset }
}
