import { cleanup, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router'
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest'
import type { TargetLibrary } from '../../api/types'
import { ResultList, UNCHECKED_GROUP_TITLE, type BatchView, type ResultSearchContext } from './ResultList'
import { LOOKUP_UNCHECKED } from './ResultRow'
import { sortBatch } from './sortBatch'
import { testRow } from './testRows'

afterEach(cleanup)

beforeAll(() => {
  // Radix positions popovers with ResizeObserver, which jsdom does not have
  globalThis.ResizeObserver ??= class {
    observe() {}
    unobserve() {}
    disconnect() {}
  }
})

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

/** Cycle 22 step 4 (R5, R7, R8, ADR-0136): the result groups and the buttons inside the results. */
describe('result groups', () => {
  const mine: TargetLibrary = { ...library(1), priorityGroup: 'MY_SIGUNGU', eligibility: 'ELIGIBLE' }
  const open: TargetLibrary = { ...library(2), visitRequired: true }
  const other: TargetLibrary = {
    ...library(3),
    priorityGroup: 'QUALIFIED',
    eligibility: 'UNKNOWN',
    eligibilitySummary: '부산 거주·직장',
  }
  const GROUPED = [mine, open, other, library(4)]

  function groupedView(
    batchNo: number,
    rows: ReturnType<typeof testRow>[],
    status: 'done' | 'loading' = 'done',
    answered = 0,
  ): BatchView {
    const context = {
      planIndex: new Map(GROUPED.map((l, index) => [l.id, index])),
      eligibility: new Map(GROUPED.map((l) => [l.id, l.eligibility])),
      cautioned: new Set<number>(),
      visitRequired: new Set(GROUPED.filter((l) => l.visitRequired).map((l) => l.id)),
      loanRates: new Map<number, number | null>(),
      chosen: new Set<number>(),
    }
    return {
      batch: { batchNo, kind: 'round', status, requestedLibraries: rows.map((r) => r.libraryId), rows },
      sorted: sortBatch(rows, context),
      answeredSoFar: answered,
    }
  }

  function context(overrides: Partial<ResultSearchContext> = {}): ResultSearchContext {
    return {
      hasMine: true,
      mineLabel: '서울 강남구',
      regionBasis: 'residence',
      scope: 'joinable',
      visit: 'any',
      accountFailed: false,
      regionsPicked: false,
      onSearchAll: vi.fn(),
      onVisitNone: vi.fn(),
      onVisitBack: vi.fn(),
      onPickRegions: vi.fn(),
      ...overrides,
    }
  }

  function renderGrouped(
    batches: BatchView[],
    searchContext?: ResultSearchContext,
    extra: { remaining?: number; grouped?: boolean } = {},
  ) {
    render(
      <MemoryRouter>
        <ResultList
          batches={batches}
          filter="all"
          onFilterChange={vi.fn()}
          libraries={GROUPED}
          searchUrls={new Map()}
          remaining={extra.remaining ?? 0}
          loading={false}
          loadingLibraries={0}
          batchFailed={false}
          onMore={vi.fn()}
          grouped={extra.grouped}
          tags
          searchContext={searchContext}
        />
      </MemoryRouter>,
    )
    return userEvent.setup()
  }

  const groupTitles = () => screen.getAllByRole('heading', { level: 2 }).map((h) => h.textContent)
  const bookTitles = (group: HTMLElement) =>
    within(group)
      .getAllByRole('heading', { level: 3 })
      .map((h) => h.textContent)
      .filter((text) => text?.startsWith('title-'))

  it('orders ① ② ③ ④ with counts, small texts and subsections; a later batch goes to the end of its subsection', () => {
    renderGrouped(
      [
        groupedView(1, [
          testRow({ libraryId: 3, rank: 1 }),
          testRow({ libraryId: 2, rank: 1 }),
          testRow({ libraryId: 1, rank: 1, availability: 'RESERVABLE', available: 0, loaned: 1 }),
          testRow({ libraryId: 4, rank: 1, availability: 'UNKNOWN', failed: true }),
        ]),
        groupedView(2, [testRow({ libraryId: 2, rank: 2 }), testRow({ libraryId: 1, rank: 2 })]),
      ],
      context({ scope: 'all' }),
    )
    expect(groupTitles()).toEqual([
      '내 도서관에 있어요 (2)',
      '어디 살든 가입되는 도서관에 있어요 (2)',
      '그 밖의 도서관에 있어요 (1)',
      `${UNCHECKED_GROUP_TITLE} (1)`,
    ])
    const mineGroup = screen.getByRole('region', { name: /내 도서관에 있어요/ })
    expect(within(mineGroup).getByText('서울 강남구에서 가입할 수 있는 곳과 직접 추가한 곳')).toBeTruthy()
    expect(within(mineGroup).getByRole('heading', { name: '지금 빌릴 수 있어요 (1)' })).toBeTruthy()
    expect(within(mineGroup).getByRole('heading', { name: '예약하고 기다려요 (1)' })).toBeTruthy()
    expect(screen.getByText('그 지역에 살거나 일해야 가입돼요')).toBeTruthy()
    // batch 2's row after batch 1's in the same subsection; no divider between the batches
    const openGroup = screen.getByRole('region', { name: /어디 살든 가입되는 도서관에 있어요/ })
    expect(within(openGroup).getByRole('heading', { name: '지금 빌릴 수 있어요 (2)' })).toBeTruthy()
    expect(bookTitles(openGroup)).toEqual(['title-2-1', 'title-2-2'])
    expect(screen.queryByText(/더 찾은 결과/)).toBeNull()
    // ③ shows the eligibility summary as a grey pill
    expect(screen.getByText('부산 거주·직장')).toBeTruthy()
  })

  it('hides empty ② and ③; an empty ① says so once the first batch is done, with [전국에서 찾기]', async () => {
    const searchContext = context()
    const user = renderGrouped([groupedView(1, [testRow({ libraryId: 2, rank: 1 })])], searchContext)
    expect(groupTitles()).toEqual(['내 도서관에는 없어요 (0)', '어디 살든 가입되는 도서관에 있어요 (1)'])
    await user.click(screen.getByRole('button', { name: '전국에서 찾기' }))
    expect(searchContext.onSearchAll).toHaveBeenCalledTimes(1)
  })

  it('draws no empty ① before the first batch is done, or for a search without regions or added libraries', () => {
    renderGrouped([groupedView(1, [testRow({ libraryId: 2, rank: 1 })], 'loading')], context())
    expect(screen.queryByText(/내 도서관에는 없어요/)).toBeNull()
    cleanup()
    renderGrouped([groupedView(1, [testRow({ libraryId: 2, rank: 1 })])], context({ hasMine: false }))
    expect(screen.queryByText(/내 도서관에는 없어요/)).toBeNull()
  })

  it('shows the green line without regions or added libraries, not for 전국 or a failed account; no ×', async () => {
    const searchContext = context({ hasMine: false })
    const user = renderGrouped([groupedView(1, [testRow({ libraryId: 2, rank: 1 })])], searchContext)
    const line = screen.getByRole('status')
    expect(line.textContent).toBe(
      '사는 곳을 고르지 않아 어디 살든 가입되는 도서관 4곳에서 찾았어요. 사는 곳을 고르면 그 지역 도서관부터 보여요 사는 곳 고르기',
    )
    expect(within(line).getAllByRole('button').map((b) => b.textContent)).toEqual(['사는 곳 고르기'])
    await user.click(within(line).getByRole('button', { name: '사는 곳 고르기' }))
    expect(searchContext.onPickRegions).toHaveBeenCalledTimes(1)
    // above the first group
    const firstGroup = screen.getAllByRole('heading', { level: 2 })[0]
    expect(line.compareDocumentPosition(firstGroup) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()

    for (const overrides of [{ scope: 'all' as const }, { accountFailed: true }, { hasMine: true }]) {
      cleanup()
      renderGrouped([groupedView(1, [testRow({ libraryId: 2, rank: 1 })])], context({ hasMine: false, ...overrides }))
      expect(screen.queryByText(/사는 곳을 고르지 않아/)).toBeNull()
    }
  })

  it('asks for [검색] once a region was checked after the search (R7-2)', () => {
    renderGrouped([groupedView(1, [testRow({ libraryId: 2, rank: 1 })])], context({ hasMine: false, regionsPicked: true }))
    expect(screen.getByRole('status').textContent).toBe('[검색]을 눌러 다시 찾아 주세요')
  })

  it('offers to remove the visit-only libraries shown in ②, then to put them back', async () => {
    const searchContext = context()
    const user = renderGrouped(
      [
        groupedView(1, [
          testRow({ libraryId: 2, rank: 1 }),
          testRow({ libraryId: 2, rank: 2 }),
          testRow({ libraryId: 1, rank: 1 }),
        ]),
      ],
      searchContext,
    )
    const openGroup = screen.getByRole('region', { name: /어디 살든 가입되는 도서관에 있어요/ })
    expect(within(openGroup).getByText(/이 중 1곳은 가입하려면 도서관에 가야 해요/)).toBeTruthy()
    await user.click(within(openGroup).getByRole('button', { name: '그 1곳 빼기' }))
    expect(searchContext.onVisitNone).toHaveBeenCalledTimes(1)

    cleanup()
    const removed = context({ visit: 'none' })
    const again = renderGrouped([groupedView(1, [testRow({ libraryId: 1, rank: 1 })])], removed)
    expect(screen.getByText(/방문 가입 도서관을 뺐어요/)).toBeTruthy()
    expect(screen.queryByText(/곳은 가입하려면/)).toBeNull()
    await again.click(screen.getByRole('button', { name: '다시 넣기' }))
    expect(removed.onVisitBack).toHaveBeenCalledTimes(1)
  })

  it('says "지금까지 N곳에서 … 찾지 못했습니다" once at the bottom above 결과 더 보기, [전국에서 더 찾기] next to it', async () => {
    const searchContext = context()
    const user = renderGrouped([groupedView(1, [], 'done', 5), groupedView(2, [], 'done', 9)], searchContext, {
      remaining: 4,
    })
    const empty = screen.getAllByText(/찾지 못했습니다/)
    expect(empty.map((p) => p.textContent)).toEqual(['지금까지 9곳에서 이 제목을 찾지 못했습니다'])
    const more = screen.getByRole('button', { name: /더 보기/ })
    expect(empty[0].compareDocumentPosition(more) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    await user.click(screen.getByRole('button', { name: '전국에서 더 찾기' }))
    expect(searchContext.onSearchAll).toHaveBeenCalledTimes(1)

    cleanup()
    renderGrouped([groupedView(1, [testRow({ libraryId: 1, rank: 1 })])], context({ scope: 'all' }), { remaining: 4 })
    expect(screen.queryByRole('button', { name: '전국에서 더 찾기' })).toBeNull()
  })

  it('grouped=false keeps the batches as they came, with the divider and no groups or 전국 button', () => {
    renderGrouped(
      [groupedView(1, [testRow({ libraryId: 1, rank: 1 })]), groupedView(2, [testRow({ libraryId: 2, rank: 1 })])],
      context(),
      { grouped: false },
    )
    expect(screen.getByText(/더 찾은 결과 · 도서관 1곳/)).toBeTruthy()
    expect(screen.queryByText(/내 도서관에 있어요/)).toBeNull()
    expect(screen.queryByText(/어디 살든 가입되는/)).toBeNull()
    expect(screen.queryByRole('button', { name: '전국에서 더 찾기' })).toBeNull()
  })
})
