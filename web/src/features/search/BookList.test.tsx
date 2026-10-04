import { cleanup, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { fetchBooks, type BookQuery } from '../../api/client'
import { holdingBatchStream, holdingStream } from '../../api/searchStream'
import type { BookPage } from '../../api/types'
import { BookList } from './BookList'
import { collectedRange } from './holdingView'

vi.mock('../../api/searchStream', () => ({ holdingStream: vi.fn(), holdingBatchStream: vi.fn() }))
vi.mock('../../api/client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../api/client')>()),
  fetchBooks: vi.fn(),
}))

afterEach(cleanup)

const QUERY: BookQuery = {
  title: '행동',
  author: null,
  publisher: null,
  exact: [],
  roles: [],
  sort: 'default',
  scope: 'all',
  visit: 'any',
  residenceRegionIds: [],
  workRegionIds: [],
  libraryIds: [],
  page: 1,
}

function page(items: BookPage['items'], overrides: Partial<BookPage> = {}): BookPage {
  return {
    items,
    page: 1,
    pageSize: 20,
    totalCount: items.length,
    hasMore: false,
    collectedFrom: '2026-09-28',
    collectedTo: '2026-09-30',
    ...overrides,
  }
}

const BEHAVE = {
  id: 7,
  title: '행동',
  authorDisplay: '로버트 M. 새폴스키',
  publisher: '문학동네',
  publishedOn: '2023-04-18',
  holdingLibraryCount: 10,
  joinTier: 'OPEN' as const,
  exactTitle: true,
}

function renderList(onDirect = vi.fn()) {
  render(
    <MemoryRouter>
      <BookList
        query={QUERY}
        bookLinkSearch="scope=joinable"
        onSortChange={vi.fn()}
        onDirect={onDirect}
        onInvalidRegion={() => Promise.resolve(false)}
      />
    </MemoryRouter>,
  )
  return { onDirect, user: userEvent.setup() }
}

describe('책 먼저 고르기 (AC-14)', () => {
  beforeEach(() => vi.mocked(fetchBooks).mockReset())

  it('lists books with their library count and asks no library', async () => {
    vi.mocked(fetchBooks).mockResolvedValue(page([BEHAVE], { totalCount: 1442, hasMore: true }))
    renderList()

    const link = await screen.findByRole('link', { name: /행동/ })
    expect(link.getAttribute('href')).toBe('/books/7?scope=joinable')
    expect(link.textContent).toContain('보유 도서관 10곳')
    expect(link.textContent).toContain('2023')
    // the collected range and count line above the results is gone (user request 2026-10-05)
    expect(screen.queryByText(/기준 수집 목록 · 책/)).toBeNull()
    expect(holdingStream).not.toHaveBeenCalled()
    expect(holdingBatchStream).not.toHaveBeenCalled()
  })

  it('"더 보기" fetches the next book page', async () => {
    vi.mocked(fetchBooks)
      .mockResolvedValueOnce(page([BEHAVE], { hasMore: true }))
      .mockResolvedValueOnce(page([{ ...BEHAVE, id: 8, title: '행동경제학' }], { page: 2 }))
    const { user } = renderList()

    await user.click(await screen.findByRole('button', { name: '더 보기' }))

    expect(await screen.findByRole('link', { name: /행동경제학/ })).toBeTruthy()
    expect(vi.mocked(fetchBooks).mock.calls[1][0].page).toBe(2)
    expect(screen.getByText('찾은 책을 모두 보여 드렸어요')).toBeTruthy()
  })

  it('no book: the empty message and the direct search button (AC-9)', async () => {
    vi.mocked(fetchBooks).mockResolvedValue(page([], { collectedFrom: null, collectedTo: null }))
    const { user, onDirect } = renderList()

    expect(await screen.findByText('조건에 맞는 책이 수집 목록에 없어요')).toBeTruthy()
    await user.click(screen.getByRole('button', { name: '도서관에서 직접 찾기' }))
    expect(onDirect).toHaveBeenCalled()
  })
})

describe('collection range', () => {
  it('one day, one month, two months', () => {
    expect(collectedRange('2026-09-30', '2026-09-30')).toBe('9월 30일 기준')
    expect(collectedRange('2026-09-28', '2026-09-30')).toBe('9월 28일~30일 기준')
    expect(collectedRange('2026-08-31', '2026-09-02')).toBe('8월 31일~9월 2일 기준')
    expect(collectedRange(null, '2026-09-30')).toBeNull()
  })
})
