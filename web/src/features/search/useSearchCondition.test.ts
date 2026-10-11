import { act, cleanup, renderHook, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { fetchLibraryPage } from '../../api/client'
import { batchStream, searchStream } from '../../api/searchStream'
import type { LibraryPage, SearchField, StreamEvent, TargetLibrary } from '../../api/types'
import { EMPTY_REGIONS } from '../region/regionStorage'
import { testRow } from './testRows'
import { useSearch } from './useSearch'

vi.mock('../../api/searchStream', () => ({ searchStream: vi.fn(), batchStream: vi.fn() }))
vi.mock('../../api/client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../api/client')>()),
  fetchLibraryPage: vi.fn(),
}))

function target(id: number): TargetLibrary {
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
    mobileSiteDiffers: false,
  }
}

function page(libraryId: number, pageNo: number, query: string): LibraryPage {
  const { libraryId: _l, page: _p, ...item } = testRow({ libraryId, rank: 1 })
  return {
    libraryId,
    query,
    page: pageNo,
    pageSize: 10,
    totalCount: 30,
    hasMore: true,
    fetchedAt: '2026-09-29T00:00:00Z',
    cached: false,
    cacheAgeSeconds: 0,
    loanRate: null,
    librarySearchUrl: 'https://x',
    items: [item],
  }
}

/** First batch: library 1 answers page 1, library 2 is left for the next batch. */
function answerFirstBatch() {
  vi.mocked(searchStream).mockImplementation(async (params, onEvent) => {
    const events: StreamEvent[] = [
      { name: 'targets', data: { [params.field]: params.query, filter: 'all', scope: 'all', visit: 'any', regionFilterApplied: false, libraries: [target(1), target(2)] } },
      { name: 'library-requested', data: { batchNo: 1, libraryId: 1, page: 1 } },
      { name: 'library-result', data: page(1, 1, params.query) },
      { name: 'done', data: { batchNo: 1 } },
    ]
    events.forEach(onEvent)
  })
}

/** Stable like the context's callback: a new function each render would restart the search. */
const resolveStoredRegions = async () => false

function renderSearch(field: SearchField, query: string) {
  return renderHook(
    (props: { field: SearchField; query: string }) =>
      useSearch({
        ...props,
        filter: 'all',
        scope: 'all',
        visit: 'any',
        regions: EMPTY_REGIONS,
        regionsReady: true,
        resolveStoredRegions,
      }),
    { initialProps: { field, query } },
  )
}

beforeEach(() => {
  vi.mocked(batchStream).mockResolvedValue()
  answerFirstBatch()
})

afterEach(() => {
  cleanup()
  vi.clearAllMocks()
})

describe('useSearch with a search condition', () => {
  it('sends the condition in the first batch', async () => {
    const { result } = renderSearch('author', '김달')

    await waitFor(() => expect(result.current.state.phase).toBe('done'))
    expect(vi.mocked(searchStream).mock.calls[0][0]).toMatchObject({ field: 'author', query: '김달' })
    expect(result.current.state.targets?.author).toBe('김달')
  })

  it('a next batch and a library "more" page use the condition of the search (AC-4)', async () => {
    vi.mocked(fetchLibraryPage).mockResolvedValue(page(1, 2, '김달'))
    const { result } = renderSearch('author', '김달')
    await waitFor(() => expect(result.current.state.phase).toBe('done'))

    await act(async () => result.current.loadLibraryMore(1))
    expect(fetchLibraryPage).toHaveBeenCalledWith(1, 'author', '김달', 2)

    act(() => result.current.loadNextBatch())
    expect(vi.mocked(batchStream).mock.calls[0][0]).toMatchObject({ field: 'author', query: '김달', batchNo: 3 })
  })

  it('a new condition drops every batch and starts again from batch 1 (AC-5)', async () => {
    const { result, rerender } = renderSearch('title', '김달')
    await waitFor(() => expect(result.current.state.phase).toBe('done'))
    act(() => result.current.loadNextBatch())
    expect(result.current.state.batches).toHaveLength(2)

    rerender({ field: 'publisher', query: '김달' })

    await waitFor(() => expect(vi.mocked(searchStream)).toHaveBeenCalledTimes(2))
    expect(vi.mocked(searchStream).mock.calls[1][0]).toMatchObject({ field: 'publisher', query: '김달' })
    await waitFor(() => expect(result.current.state.phase).toBe('done'))
    expect(result.current.state.field).toBe('publisher')
    expect(result.current.state.batches.map((b) => b.batchNo)).toEqual([1])
  })
})
