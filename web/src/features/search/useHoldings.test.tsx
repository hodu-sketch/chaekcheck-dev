import { act, cleanup, renderHook } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { holdingBatchStream, holdingStream } from '../../api/searchStream'
import type { BookItem, HoldingStreamEvent, TargetLibrary } from '../../api/types'
import { useHoldings } from './useHoldings'

vi.mock('../../api/searchStream', () => ({ holdingStream: vi.fn(), holdingBatchStream: vi.fn() }))

afterEach(cleanup)

const BOOK: BookItem = {
  id: 1,
  title: '행동',
  authorDisplay: null,
  publisher: null,
  publishedOn: null,
  holdingLibraryCount: 200,
  joinTier: 'OPEN',
  exactTitle: true,
}

function library(id: number): TargetLibrary {
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
    joinMethod: null,
    joinUrl: null,
    joinCaution: null,
    joinNotice: null,
    homepageUrl: null,
    chosen: false,
    joinSurvey: 'SURVEYED',
    logoUrl: null,
    mobileSiteDiffers: false,
  }
}

/** A stream that answers every holding it was given (holding id = library id), then ends. */
function answers(holdingIds: number[], batchNo: number): HoldingStreamEvent[] {
  return [
    ...holdingIds.flatMap<HoldingStreamEvent>((id) => [
      { name: 'holding-requested', data: { batchNo, holdingId: id, bookId: BOOK.id, libraryId: id } },
      {
        name: 'holding-result',
        data: {
          batchNo,
          holdingId: id,
          bookId: BOOK.id,
          libraryId: id,
          fetchedAt: '',
          cached: false,
          item: {
            rank: 0,
            title: BOOK.title,
            author: null,
            publisher: null,
            supplier: null,
            holdings: 1,
            loaned: 0,
            reservations: 0,
            available: 1,
            titleKey: `book:${BOOK.id}`,
            availability: 'AVAILABLE',
            availabilityBasis: 'COUNT',
            detailUrl: null,
          },
        },
      },
    ]),
    { name: 'done', data: { batchNo } },
  ]
}

/** The server asks this many holdings a batch (`holding-batch-size`), the first ones it is given. */
const SERVER_BATCH = 10

function mockStreams(total: number) {
  const ids = Array.from({ length: total }, (_, i) => i + 1)
  vi.mocked(holdingStream).mockImplementation(async (_params, onEvent) => {
    onEvent({
      name: 'targets',
      data: {
        filter: 'all',
        scope: 'all',
        visit: 'any',
        regionFilterApplied: false,
        libraries: ids.map(library),
        holdings: ids.map((id) => ({ holdingId: id, bookId: BOOK.id, libraryId: id })),
      },
    })
    for (const event of answers(ids.slice(0, SERVER_BATCH), 1)) onEvent(event)
  })
  vi.mocked(holdingBatchStream).mockImplementation(async (params, onEvent) => {
    for (const event of answers(params.candidates.slice(0, SERVER_BATCH), params.batchNo)) onEvent(event)
  })
}

const PLAN = {
  filter: 'all' as const,
  scope: 'all' as const,
  visit: 'any' as const,
  residenceRegionIds: [],
  workRegionIds: [],
  libraryIds: [],
}

/** User decision 2026-10-18 (replaces cycle 20 R15-1's three batches): one batch per search, one per "더 보기". */
describe('holding batches', () => {
  beforeEach(() => {
    vi.mocked(holdingStream).mockReset()
    vi.mocked(holdingBatchStream).mockReset()
  })

  it('asks one batch for a search and never goes on by itself', async () => {
    mockStreams(105)
    const { result } = renderHook(() => useHoldings())
    await act(async () => result.current.open([BOOK], PLAN))
    await act(async () => {})

    expect(holdingStream).toHaveBeenCalledTimes(1)
    expect(holdingBatchStream).not.toHaveBeenCalled()
    expect(result.current.state.phase).toBe('done')
    expect(result.current.state.batches.flatMap((b) => b.rows)).toHaveLength(SERVER_BATCH)
    expect(result.current.remaining).toBe(105 - SERVER_BATCH)
  })

  it('"더 보기" asks the next holdings in order, one batch each time', async () => {
    mockStreams(25)
    const { result } = renderHook(() => useHoldings())
    await act(async () => result.current.open([BOOK], PLAN))
    await act(async () => {})

    await act(async () => {
      expect(result.current.loadNext()).toBe(true)
    })
    await act(async () => {})
    expect(holdingBatchStream).toHaveBeenCalledTimes(1)
    expect(vi.mocked(holdingBatchStream).mock.calls[0][0].candidates.slice(0, 3)).toEqual([11, 12, 13])
    expect(result.current.remaining).toBe(5)

    await act(async () => {
      expect(result.current.loadNext()).toBe(true)
    })
    await act(async () => {})
    expect(holdingBatchStream).toHaveBeenCalledTimes(2)
    expect(result.current.remaining).toBe(0)
    expect(result.current.loadNext()).toBe(false)
  })

  it('a batch that broke off leaves its holdings for "더 보기"', async () => {
    mockStreams(25)
    const { result } = renderHook(() => useHoldings())
    await act(async () => result.current.open([BOOK], PLAN))
    await act(async () => {})
    vi.mocked(holdingBatchStream).mockRejectedValue(new Error('offline'))
    await act(async () => {
      result.current.loadNext()
    })
    await act(async () => {})
    expect(result.current.state.batchFailed).toBe(true)
    expect(result.current.remaining).toBe(15)
  })
})
