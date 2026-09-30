import { describe, expect, it } from 'vitest'
import type { LibraryPage } from '../../api/types'
import { initialPlan, markFailed, markPage, markRequested, nextCandidates, remainingLibraries } from './searchPlan'

function page(libraryId: number, pageNo: number, hasMore: boolean): LibraryPage {
  return {
    libraryId,
    query: '사랑',
    page: pageNo,
    pageSize: 10,
    totalCount: 30,
    hasMore,
    fetchedAt: '2026-09-28T00:00:00Z',
    cached: false,
    cacheAgeSeconds: 0,
    loanRate: null,
    librarySearchUrl: 'https://x',
    items: [],
  }
}

describe('nextCandidates (design-delta "도서관 하나의 조회 상태")', () => {
  it('first round: libraries not requested yet, in plan order, page 1 (AC-4)', () => {
    let plan = initialPlan([5, 3, 9, 1])
    plan = markPage(markRequested(plan, 5), page(5, 1, true))

    expect(nextCandidates(plan, 50)).toEqual([
      { libraryId: 3, page: 1 },
      { libraryId: 9, page: 1 },
      { libraryId: 1, page: 1 },
    ])
  })

  it('after one full round: libraries with more pages, their next page (AC-5)', () => {
    let plan = initialPlan([5, 3, 9])
    plan = markPage(plan, page(5, 1, true))
    plan = markPage(plan, page(3, 1, false))
    plan = markPage(plan, page(9, 1, true))

    expect(nextCandidates(plan, 50)).toEqual([
      { libraryId: 5, page: 2 },
      { libraryId: 9, page: 2 },
    ])
  })

  it('failed libraries never come back', () => {
    let plan = initialPlan([5, 3])
    plan = markFailed(markRequested(plan, 5), 5)
    plan = markPage(plan, page(3, 1, true))

    expect(nextCandidates(plan, 50)).toEqual([{ libraryId: 3, page: 2 }])
  })

  it('candidates the server did not get to stay not requested', () => {
    let plan = initialPlan([1, 2, 3])
    plan = markPage(markRequested(plan, 1), page(1, 1, true))
    // the server stopped at the row limit before 2 and 3

    expect(nextCandidates(plan, 50).map((c) => c.libraryId)).toEqual([2, 3])
  })

  it('takes at most K', () => {
    const plan = initialPlan(Array.from({ length: 80 }, (_, i) => i + 1))

    expect(nextCandidates(plan, 50)).toHaveLength(50)
  })

  it('a library "more" page moves the next page on, so round robin does not ask it again (AC-6)', () => {
    let plan = initialPlan([1])
    plan = markPage(plan, page(1, 1, true))
    plan = markPage(plan, page(1, 2, true)) // per-library "more"

    expect(nextCandidates(plan, 50)).toEqual([{ libraryId: 1, page: 3 }])
  })

  it('counts libraries that still have something to fetch', () => {
    let plan = initialPlan([1, 2, 3, 4])
    plan = markPage(plan, page(1, 1, true))
    plan = markPage(plan, page(2, 1, false))
    plan = markFailed(plan, 3)

    expect(remainingLibraries(plan)).toBe(2)
  })
})
