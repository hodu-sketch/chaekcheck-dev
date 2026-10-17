import { act, cleanup, renderHook } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { holdingBatchStream, holdingStream } from '../../api/searchStream'
import type { BookItem, HoldingStreamEvent, TargetLibrary } from '../../api/types'
import { HOLDING_AUTO_BATCHES, useHoldings } from './useHoldings'
import { MAX_CANDIDATES } from './useSearch'

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
    for (const event of answers(ids.slice(0, MAX_CANDIDATES), 1)) onEvent(event)
  })
  vi.mocked(holdingBatchStream).mockImplementation(async (params, onEvent) => {
    for (const event of answers(params.candidates, params.batchNo)) onEvent(event)
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

/** Cycle 20 spec R15-1 (전반 점검 #7 [확정]): batches go on by themselves up to three per run, then "더 보기". */
describe('holding batches that continue by themselves', () => {
  beforeEach(() => {
    vi.mocked(holdingStream).mockReset()
    vi.mocked(holdingBatchStream).mockReset()
  })

  it('runs three batches, stops with holdings left, and "더 보기" runs up to three more (AC-36)', async () => {
    expect(HOLDING_AUTO_BATCHES).toBe(3)
    mockStreams(MAX_CANDIDATES * 7)
    const { result } = renderHook(() => useHoldings())
    await act(async () => result.current.open([BOOK], PLAN))
    await act(async () => {})

    expect(holdingStream).toHaveBeenCalledTimes(1)
    expect(holdingBatchStream).toHaveBeenCalledTimes(HOLDING_AUTO_BATCHES - 1)
    expect(result.current.state.phase).toBe('done')
    expect(result.current.state.batches.map((b) => b.batchNo)).toEqual([1, 2, 3])
    expect(result.current.remaining).toBe(MAX_CANDIDATES * 4)

    await act(async () => {
      expect(result.current.loadNext()).toBe(true)
    })
    await act(async () => {})
    expect(holdingBatchStream).toHaveBeenCalledTimes(HOLDING_AUTO_BATCHES - 1 + HOLDING_AUTO_BATCHES)
    expect(result.current.state.batches.map((b) => b.batchNo)).toEqual([1, 2, 3, 4, 5, 6])
    expect(result.current.remaining).toBe(MAX_CANDIDATES)
    expect(result.current.state.phase).toBe('done')
  })

  it('continues only while holdings are left (AC-28)', async () => {
    mockStreams(MAX_CANDIDATES + 10)
    const { result } = renderHook(() => useHoldings())
    await act(async () => result.current.open([BOOK], PLAN))
    await act(async () => {})

    expect(holdingBatchStream).toHaveBeenCalledTimes(1)
    expect(vi.mocked(holdingBatchStream).mock.calls[0][0].candidates).toHaveLength(10)
    expect(result.current.remaining).toBe(0)
    expect(result.current.state.phase).toBe('done')
    expect(result.current.loadNext()).toBe(false)
  })

  it('does not continue when nothing is left after the first batch (AC-28)', async () => {
    mockStreams(3)
    const { result } = renderHook(() => useHoldings())
    await act(async () => result.current.open([BOOK], PLAN))
    await act(async () => {})
    expect(holdingBatchStream).not.toHaveBeenCalled()
    expect(result.current.remaining).toBe(0)
  })

  it('does not continue after a batch that broke off', async () => {
    mockStreams(MAX_CANDIDATES * 3)
    vi.mocked(holdingBatchStream).mockRejectedValue(new Error('offline'))
    const { result } = renderHook(() => useHoldings())
    await act(async () => result.current.open([BOOK], PLAN))
    await act(async () => {})
    expect(holdingBatchStream).toHaveBeenCalledTimes(1)
    expect(result.current.state.batchFailed).toBe(true)
    expect(result.current.remaining).toBe(MAX_CANDIDATES * 2)
  })
})
