import { describe, expect, it } from 'vitest'
import type { LibraryPage, TargetLibrary } from '../../api/types'
import { reducer, type SearchState } from './useSearch'
import { testRow } from './testRows'

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
  }
}

function page(libraryId: number, pageNo: number): LibraryPage {
  const { libraryId: _l, page: _p, ...item } = testRow({ libraryId, rank: 1 })
  return {
    libraryId,
    query: '사랑',
    page: pageNo,
    pageSize: 10,
    totalCount: 30,
    hasMore: true,
    fetchedAt: '2026-09-28T00:00:00Z',
    cached: false,
    cacheAgeSeconds: 0,
    loanRate: 0.5,
    librarySearchUrl: 'https://x',
    items: [item],
  }
}

function firstBatch(): SearchState {
  let state = reducer(undefined as unknown as SearchState, { type: 'reset' })
  state = reducer(state, { type: 'start', field: 'title', query: '사랑', filter: 'all' })
  state = reducer(state, {
    type: 'event',
    batchNo: 1,
    event: { name: 'targets', data: { title: '사랑', filter: 'all', scope: 'all', visit: 'any', regionFilterApplied: false, libraries: [target(1), target(2)] } },
  })
  state = reducer(state, { type: 'event', batchNo: 1, event: { name: 'library-requested', data: { batchNo: 1, libraryId: 1, page: 1 } } })
  state = reducer(state, { type: 'event', batchNo: 1, event: { name: 'library-result', data: page(1, 1) } })
  return reducer(state, { type: 'event', batchNo: 1, event: { name: 'done', data: { batchNo: 1 } } })
}

describe('search reducer', () => {
  it('keeps the plan, the requested libraries and the batch rows', () => {
    const state = firstBatch()

    expect(state.phase).toBe('done')
    expect(state.plan.entries[1]).toEqual({ status: 'HasMore', nextPage: 2 })
    expect(state.plan.entries[2]).toEqual({ status: 'NotRequested', nextPage: 1 })
    expect(Object.keys(state.libraries)).toEqual(['1'])
    expect(state.batches).toHaveLength(1)
    expect(state.batches[0].rows).toHaveLength(1)
    expect(state.batches[0].status).toBe('done')
  })

  it('a next batch is appended; earlier batches keep their rows (AC-4, AC-24)', () => {
    let state = firstBatch()
    const before = state.batches[0]
    state = reducer(state, { type: 'batch-start', batchNo: 2 })
    state = reducer(state, { type: 'event', batchNo: 2, event: { name: 'library-requested', data: { batchNo: 2, libraryId: 2, page: 1 } } })
    state = reducer(state, { type: 'event', batchNo: 2, event: { name: 'library-result', data: page(2, 1) } })

    expect(state.batches.map((b) => b.batchNo)).toEqual([1, 2])
    expect(state.batches[0]).toBe(before)
    expect(state.batches[1].rows.map((r) => r.libraryId)).toEqual([2])
  })

  it('a library "more" page becomes a new batch at the bottom (AC-6)', () => {
    let state = firstBatch()
    state = reducer(state, { type: 'more-start', libraryId: 1 })
    state = reducer(state, { type: 'more-done', page: page(1, 2) })

    expect(state.batches.map((b) => [b.batchNo, b.kind])).toEqual([
      [1, 'round'],
      [2, 'library'],
    ])
    expect(state.plan.entries[1].nextPage).toBe(3)
    expect(state.libraries[1].loanRate).toBe(0.5)
    expect(state.libraries[1].pages.map((p) => p.page)).toEqual([1, 2])
  })

  it('a new search (filter or regions changed) drops every batch [확정]', () => {
    const state = reducer(firstBatch(), { type: 'start', field: 'title', query: '사랑', filter: 'available' })

    expect(state.batches).toHaveLength(1)
    expect(state.batches[0].rows).toEqual([])
    expect(state.libraries).toEqual({})
    expect(state.filter).toBe('available')
  })

  it('a failed library is marked failed in the plan', () => {
    const state = reducer(firstBatch(), {
      type: 'event',
      batchNo: 1,
      event: {
        name: 'library-failed',
        data: { batchNo: 1, libraryId: 2, page: 1, problem: { title: 'x', status: 504, code: 'upstream_timeout' } },
      },
    })

    expect(state.plan.entries[2].status).toBe('Failed')
    expect(state.libraries[2].status).toBe('failed')
  })
})
