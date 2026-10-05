import { describe, expect, it } from 'vitest'
import type { BookItem, HoldingStreamEvent, LibraryItem, TargetLibrary } from '../../api/types'
import {
  EMPTY_HOLDINGS,
  holdingsReducer,
  nextHoldingCandidates,
  remainingHoldings,
  type HoldingsAction,
  type HoldingsState,
} from './holdingPlan'
import { holdingBatchViews } from './holdingView'

function library(id: number, overrides: Partial<TargetLibrary> = {}): TargetLibrary {
  return {
    id,
    name: `도서관${id}`,
    libraryType: 'EBOOK',
    platformType: 'KYOBO_V1',
    priorityGroup: 'OPEN',
    deferred: false,
    eligibility: 'OPEN',
    eligibilitySummary: null,
    extraConditionText: null,
    visitRequired: false,
    joinMethod: '홈페이지',
    joinUrl: null,
    joinCaution: null,
    joinNotice: null,
    homepageUrl: null,
    chosen: false,
    joinSurvey: 'SURVEYED',
    logoUrl: null,
    ...overrides,
  }
}

function item(title: string, holdings: number, loaned: number): LibraryItem {
  return {
    rank: 0,
    title,
    author: null,
    publisher: null,
    supplier: null,
    holdings,
    loaned,
    reservations: 0,
    available: holdings - loaned,
    titleKey: `book:${title}`,
    availability: holdings - loaned > 0 ? 'AVAILABLE' : 'RESERVABLE',
    availabilityBasis: 'COUNT',
    detailUrl: 'https://lib.test/d',
  }
}

const BOOKS = new Map<number, BookItem>([
  [10, { id: 10, title: '행동', authorDisplay: '새폴스키', publisher: '문학동네', publishedOn: null, holdingLibraryCount: 2, joinTier: 'OPEN', exactTitle: true }],
])

function run(state: HoldingsState, batchNo: number, ...events: HoldingStreamEvent[]): HoldingsState {
  return events.reduce(
    (current, event) => holdingsReducer(current, { type: 'event', batchNo, event, books: BOOKS } as HoldingsAction),
    state,
  )
}

describe('holding plan (ADR-0030)', () => {
  const targets: HoldingStreamEvent = {
    name: 'targets',
    data: {
      filter: 'all',
      scope: 'all',
      visit: 'any',
      regionFilterApplied: false,
      libraries: [library(1), library(2, { eligibility: 'UNVERIFIED', priorityGroup: 'NEEDS_CHECK' })],
      holdings: [
        { holdingId: 101, bookId: 10, libraryId: 2 },
        { holdingId: 100, bookId: 10, libraryId: 1 },
        { holdingId: 102, bookId: 11, libraryId: 1 },
      ],
    },
  }

  it('keeps holdings in stream order and asks the not-asked ones next', () => {
    let state = holdingsReducer(EMPTY_HOLDINGS, { type: 'batch-start', batchNo: 1 })
    state = run(
      state,
      1,
      targets,
      { name: 'holding-requested', data: { batchNo: 1, holdingId: 101, bookId: 10, libraryId: 2 } },
      { name: 'holding-result', data: { batchNo: 1, holdingId: 101, bookId: 10, libraryId: 2, item: item('행동', 1, 1), fetchedAt: '', cached: false } },
      { name: 'done', data: { batchNo: 1 } },
    )

    expect(state.phase).toBe('done')
    expect(remainingHoldings(state)).toBe(2)
    expect(nextHoldingCandidates(state, 50)).toEqual([100, 102])
    expect(nextHoldingCandidates(state, 1)).toEqual([100])
  })

  it('a failed holding is one "확인 실패" row with the book it belongs to', () => {
    let state = holdingsReducer(EMPTY_HOLDINGS, { type: 'batch-start', batchNo: 1 })
    state = run(state, 1, targets, {
      name: 'holding-failed',
      data: { batchNo: 1, holdingId: 100, bookId: 10, libraryId: 1, problem: { title: 'Bad Gateway', status: 502, code: 'upstream_error' } },
    })

    const row = state.batches[0].rows[0]
    expect(row).toMatchObject({ failed: true, title: '행동', libraryId: 1, availability: 'UNKNOWN', titleKey: 'book:10' })
    expect(state.entries[100].status).toBe('Failed')
    expect(nextHoldingCandidates(state, 50)).toEqual([101, 102])
  })

  it('a later book page adds its libraries and holdings after the earlier ones', () => {
    let state = run(holdingsReducer(EMPTY_HOLDINGS, { type: 'batch-start', batchNo: 1 }), 1, targets)
    state = holdingsReducer(state, { type: 'batch-start', batchNo: 2 })
    state = run(state, 2, {
      name: 'targets',
      data: { ...targets.data, libraries: [library(1), library(3)], holdings: [{ holdingId: 200, bookId: 12, libraryId: 3 }] },
    } as HoldingStreamEvent)

    expect(state.libraries.map((l) => l.id)).toEqual([1, 2, 3])
    expect(state.order).toEqual([101, 100, 102, 200])
  })

  it('rows of one book group together; joinable libraries come first (cycle 02·03 sorting)', () => {
    let state = holdingsReducer(EMPTY_HOLDINGS, { type: 'batch-start', batchNo: 1 })
    state = run(
      state,
      1,
      targets,
      { name: 'holding-result', data: { batchNo: 1, holdingId: 101, bookId: 10, libraryId: 2, item: item('행동', 3, 0), fetchedAt: '', cached: false } },
      { name: 'holding-result', data: { batchNo: 1, holdingId: 100, bookId: 10, libraryId: 1, item: item('행동', 1, 0), fetchedAt: '', cached: false } },
    )

    const [view] = holdingBatchViews(state, 'all')
    expect(view.sorted.available.map((row) => row.libraryId)).toEqual([1, 2])
    expect(holdingBatchViews(state, 'available')[0].sorted.reserve).toEqual([])
  })

  it('a stream that breaks off marks the batch failed', () => {
    let state = holdingsReducer(EMPTY_HOLDINGS, { type: 'batch-start', batchNo: 1 })
    state = holdingsReducer(state, { type: 'batch-failed', batchNo: 1 })

    expect(state.batchFailed).toBe(true)
    expect(state.phase).toBe('done')
  })
})
