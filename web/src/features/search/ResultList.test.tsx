import { cleanup, render, screen, within } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { TargetLibrary } from '../../api/types'
import { ResultList, UNCHECKED_GROUP_TITLE, type BatchView } from './ResultList'
import { LOOKUP_UNCHECKED } from './ResultRow'
import { sortBatch } from './sortBatch'
import { testRow } from './testRows'

afterEach(cleanup)

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
    joinMethod: '홈페이지',
    joinUrl: null,
    joinCaution: null,
    joinNotice: null,
    homepageUrl: `https://lib${id}.example/`,
    chosen: false,
    joinSurvey: 'SURVEYED',
    logoUrl: null,
    mobileSiteDiffers: false,
  }
}

const LIBRARIES = [library(1), library(2), library(3), library(4)]

function view(batchNo: number, rows: ReturnType<typeof testRow>[]): BatchView {
  const context = {
    planIndex: new Map(LIBRARIES.map((l, index) => [l.id, index])),
    eligibility: new Map(LIBRARIES.map((l) => [l.id, l.eligibility])),
    cautioned: new Set<number>(),
    visitRequired: new Set<number>(),
    loanRates: new Map<number, number | null>(),
    chosen: new Set<number>(),
  }
  return {
    batch: { batchNo, kind: 'round', status: 'done', requestedLibraries: rows.map((r) => r.libraryId), rows },
    sorted: sortBatch(rows, context),
    answeredSoFar: 0,
  }
}

/** Cycle 20 handoff 1-13 [확정] (review #2): one group at the bottom, right above "더 보기", in plan order. */
describe('확인하지 못한 도서관 group', () => {
  it('gathers failed and UNKNOWN rows of every batch and failed libraries above 더 보기, in plan order', () => {
    render(
      <ResultList
        batches={[
          view(1, [testRow({ libraryId: 4, rank: 1, availability: 'UNKNOWN', failed: true }), testRow({ libraryId: 1, rank: 1 })]),
          view(2, [testRow({ libraryId: 2, rank: 1, availability: 'UNKNOWN', availabilityBasis: 'SITE_DETAIL' })]),
        ]}
        filter="available"
        onFilterChange={vi.fn()}
        libraries={LIBRARIES}
        searchUrls={new Map()}
        remaining={3}
        loading={false}
        loadingLibraries={0}
        batchFailed={false}
        onMore={vi.fn()}
        uncheckedLibraries={[{ library: LIBRARIES[2], url: LIBRARIES[2].homepageUrl }]}
      />,
    )
    const group = screen.getByRole('region', { name: new RegExp(UNCHECKED_GROUP_TITLE) })
    const names = within(group)
      .getAllByRole('article')
      .map((a) => a.querySelector('button, span')?.textContent)
    expect(names).toEqual(['도서관2', '도서관3', '도서관4'])
    expect(within(group).getAllByText(new RegExp(LOOKUP_UNCHECKED)).length).toBe(2)
    const more = screen.getByRole('button', { name: /더 보기/ })
    expect(group.compareDocumentPosition(more) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    // the normal row of batch 1 is not in the group
    expect(within(group).queryByText('title-1-1')).toBeNull()
    expect(screen.getByText('title-1-1')).toBeTruthy()
  })

  // user request 2026-10-18: 바로 보기's button says what a tap does, what is left on a second line
  it('says what "더 보기" does when a label is given', () => {
    render(
      <ResultList
        batches={[view(1, [testRow({ libraryId: 1, rank: 1 })])]}
        filter="all"
        onFilterChange={vi.fn()}
        libraries={LIBRARIES}
        searchUrls={new Map()}
        remaining={12}
        loading={false}
        loadingLibraries={0}
        batchFailed={false}
        onMore={vi.fn()}
        moreLabel="결과 더 보기"
        moreText="이 책들을 가진 도서관 12곳 남음"
      />,
    )
    const more = screen.getByRole('button', { name: /결과 더 보기/ })
    expect(more.textContent).toBe('결과 더 보기이 책들을 가진 도서관 12곳 남음')
    expect(screen.queryByText(/남은 도서관 줄/)).toBeNull()
  })

  it('draws no group when nothing failed', () => {
    render(
      <ResultList
        batches={[view(1, [testRow({ libraryId: 1, rank: 1 })])]}
        filter="all"
        onFilterChange={vi.fn()}
        libraries={LIBRARIES}
        searchUrls={new Map()}
        remaining={0}
        loading={false}
        loadingLibraries={0}
        batchFailed={false}
        onMore={vi.fn()}
      />,
    )
    expect(screen.queryByText(UNCHECKED_GROUP_TITLE)).toBeNull()
  })
})
