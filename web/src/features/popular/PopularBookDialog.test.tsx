import { cleanup, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError, fetchBook, type PlanParams } from '../../api/client'
import { holdingStream } from '../../api/searchStream'
import type { BookDetail, HoldingStreamEvent, LibraryItem, TargetLibrary } from '../../api/types'
import { PopularBookDialog } from './PopularBookDialog'
import { testBooks } from './testPopular'

vi.mock('../../api/searchStream', () => ({ holdingStream: vi.fn(), holdingBatchStream: vi.fn() }))
vi.mock('../../api/client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../api/client')>()),
  fetchBook: vi.fn(),
}))

beforeAll(() => {
  globalThis.ResizeObserver ??= class {
    observe() {}
    unobserve() {}
    disconnect() {}
  }
})

afterEach(cleanup)

const PLAN: PlanParams = { scope: 'joinable', visit: 'any', residenceRegionIds: [1116], workRegionIds: [], libraryIds: [12] }
const BOOK = testBooks('소설')[0]

const DETAIL: BookDetail = {
  id: 5001,
  title: '우리 전자책 제목',
  authorDisplay: '지은이',
  publisher: '출판사',
  publishedOn: '2026-01-01',
  isbn13: '9780000000001',
  holdingLibraryCount: 5,
  libraries: [],
}

function library(id: number, name: string): TargetLibrary {
  return {
    id,
    name,
    libraryType: 'EBOOK',
    platformType: 'KYOBO_V1',
    priorityGroup: 'OPEN',
    deferred: false,
    extraConditionText: null,
    visitRequired: false,
    eligibility: 'OPEN',
    eligibilitySummary: null,
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

function item(overrides: Partial<LibraryItem>): LibraryItem {
  return {
    rank: 0,
    title: '우리 전자책 제목',
    author: '지은이',
    publisher: '출판사',
    supplier: null,
    holdings: 2,
    loaned: 2,
    reservations: 0,
    available: 0,
    titleKey: 'book:5001',
    availability: 'RESERVABLE',
    availabilityBasis: 'COUNT',
    detailUrl: null,
    ...overrides,
  }
}

/** The first three plan holdings: 가 on loan (3 waiting), 나 on loan (1 waiting), 다 borrowable now. */
function answerWithThreeHoldings() {
  vi.mocked(holdingStream).mockImplementation(async (_params, onEvent) => {
    const libraries = [library(1, '가 도서관'), library(2, '나 도서관'), library(3, '다 도서관')]
    const events: HoldingStreamEvent[] = [
      {
        name: 'targets',
        data: {
          filter: 'all',
          scope: 'joinable',
          visit: 'any',
          regionFilterApplied: true,
          libraries,
          holdings: libraries.map((l) => ({ holdingId: 100 + l.id, bookId: 5001, libraryId: l.id })),
        },
      },
      ...[
        { libraryId: 1, item: item({ reservations: 3 }) },
        { libraryId: 2, item: item({ reservations: 1 }) },
        {
          libraryId: 3,
          item: item({ availability: 'AVAILABLE', available: 1, loaned: 1, detailUrl: 'https://ebook.example.org/book/1' }),
        },
      ].map(
        ({ libraryId, item: result }): HoldingStreamEvent => ({
          name: 'holding-result',
          data: { batchNo: 1, holdingId: 100 + libraryId, bookId: 5001, libraryId, item: result, fetchedAt: '', cached: false },
        }),
      ),
      { name: 'done', data: { batchNo: 1 } },
    ]
    for (const event of events) onEvent(event)
  })
}

function renderDialog(onSearch = vi.fn()) {
  render(<PopularBookDialog book={BOOK} plan={PLAN} onClose={vi.fn()} onSearch={onSearch} phone={false} />)
  return userEvent.setup()
}

beforeEach(() => {
  vi.mocked(fetchBook).mockReset()
  vi.mocked(holdingStream).mockReset()
})

/** S14 (R6-1~R6-8, AC-8, AC-9, AC-21). */
describe('popular book window', () => {
  it('asks only the first three plan holdings and lists them borrowable first (AC-8)', async () => {
    vi.mocked(fetchBook).mockResolvedValue(DETAIL)
    answerWithThreeHoldings()
    renderDialog()
    const dialog = screen.getByRole('dialog', { name: '소설 1 대출 정보' })
    expect(await within(dialog).findAllByRole('article')).toHaveLength(3)
    expect(within(dialog).getAllByText('지은이 · 출판사').length).toBeGreaterThan(0)
    expect(vi.mocked(fetchBook).mock.calls[0].slice(0, 2)).toEqual(['5001', PLAN])
    expect(vi.mocked(holdingStream).mock.calls[0][0]).toMatchObject({ bookIds: [5001], holdingLimit: 3, libraryIds: [12] })

    const rows = within(dialog).getAllByRole('article')
    expect(rows.map((row) => within(row).getByRole('button', { name: /가입 정보$/ }).textContent)).toEqual([
      '다 도서관',
      '나 도서관',
      '가 도서관',
    ])
    expect(within(dialog).queryByText(/외 \d+곳/)).toBeNull()
    expect(within(dialog).queryByText('확인 중…')).toBeNull()
  })

  it('a library line opens its book page in a new tab; [책 검색 >>] asks for the book screen (AC-9)', async () => {
    vi.mocked(fetchBook).mockResolvedValue(DETAIL)
    answerWithThreeHoldings()
    const onSearch = vi.fn()
    const user = renderDialog(onSearch)
    const link = await screen.findByRole('link', { name: '다 도서관에서 보기 (새 창)' })
    expect(link.getAttribute('href')).toBe('https://ebook.example.org/book/1')
    expect(link.getAttribute('target')).toBe('_blank')
    await user.click(screen.getByRole('button', { name: '책 검색' }))
    expect(onSearch).toHaveBeenCalledWith(5001)
  })

  it('says 확인 중 before the answers', async () => {
    vi.mocked(fetchBook).mockResolvedValue(DETAIL)
    vi.mocked(holdingStream).mockImplementation(() => new Promise(() => {}))
    renderDialog()
    expect(await screen.findByText('확인 중…')).toBeTruthy()
    expect(screen.getByRole('heading', { name: '우리 전자책 제목' })).toBeTruthy()
  })

  it('a book that left the lists: the book screen words (AC-21)', async () => {
    vi.mocked(fetchBook).mockRejectedValue(new ApiError(404, { title: 'Not Found', status: 404, code: 'book_not_found' }))
    renderDialog()
    expect(await screen.findByText('책을 찾을 수 없어요')).toBeTruthy()
    expect(screen.queryByRole('button', { name: '책 검색' })).toBeNull()
    expect(holdingStream).not.toHaveBeenCalled()
  })

  it('any other failure: the book screen error words (AC-21)', async () => {
    vi.mocked(fetchBook).mockRejectedValue(new TypeError('Failed to fetch'))
    renderDialog()
    expect(await screen.findByText('책 정보를 불러오지 못했어요. 잠시 뒤 다시 시도해 주세요.')).toBeTruthy()
  })

  it('no plan library holds it any more: no lookup, the S3 words', async () => {
    vi.mocked(fetchBook).mockResolvedValue({ ...DETAIL, holdingLibraryCount: 0 })
    renderDialog()
    expect(await screen.findByText('조건에 맞는 도서관이 없어요')).toBeTruthy()
    expect(holdingStream).not.toHaveBeenCalled()
  })
})
