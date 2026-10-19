import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router'
import { afterAll, afterEach, beforeAll, describe, expect, it, vi } from 'vitest'
import type { TargetLibrary } from '../../api/types'
import { sendUsageEvent } from '../../api/usageEvents'
import { LibraryLogo } from '../../components/LibraryLogo'
import { LibrarySummaryBar } from './LibrarySummaryBar'
import { ResultRow } from './ResultRow'
import { testRow } from './testRows'

/**
 * Cycle 15 step 8: the six clicked places report themselves and still open their new tab (AC-7, AC-8); 「대여하기」 in
 * place of the whole card since 2026-10-19, the same place `result_card`.
 */

vi.mock('../../api/usageEvents', () => ({ sendUsageEvent: vi.fn() }))

afterEach(() => {
  cleanup()
  vi.mocked(sendUsageEvent).mockClear()
})

beforeAll(() => {
  // Radix positions popovers with ResizeObserver, which jsdom does not have
  globalThis.ResizeObserver ??= class {
    observe() {}
    unobserve() {}
    disconnect() {}
  }
})

const library: TargetLibrary = {
  id: 12,
  name: '고흥군립도서관 전자도서관',
  libraryType: 'EBOOK',
  platformType: 'KYOBO_V1',
  priorityGroup: 'OPEN',
  deferred: false,
  extraConditionText: null,
  visitRequired: false,
  eligibility: 'OPEN',
  eligibilitySummary: null,
  joinMethod: '홈페이지 가입',
  joinUrl: 'https://lib.example/join',
  joinCaution: null,
  joinNotice: null,
  homepageUrl: 'https://lib.example/',
  chosen: false,
  joinSurvey: 'SURVEYED',
  logoUrl: '/library-logos/12.png',
  mobileSiteDiffers: false,
}

const row = testRow({ libraryId: 12, rank: 1, detailUrl: 'https://lib.example/book/1', bookId: 345 })

/** Clicks like a person; true when the link was left to open (nothing prevented). */
function clickOpens(link: HTMLElement): boolean {
  expect(link.getAttribute('target')).toBe('_blank')
  return fireEvent.click(link)
}

async function openJoinDetails() {
  await userEvent.setup().click(screen.getByRole('button', { name: `${library.name} 가입 정보` }))
}

describe('result line links', () => {
  it('「대여하기」 on the book page sends the book (AC-8)', () => {
    render(<ResultRow row={row} library={library} searchUrl={undefined} screen="book_page" />)

    expect(clickOpens(screen.getByRole('link', { name: /대여하기/ }))).toBe(true)

    expect(sendUsageEvent).toHaveBeenCalledExactlyOnceWith({
      type: 'library_click',
      libraryId: 12,
      place: 'result_card',
      screen: 'book_page',
      bookId: 345,
    })
  })

  it('a direct search line sends no book (AC-8)', () => {
    render(
      <ResultRow row={{ ...row, bookId: undefined }} library={library} searchUrl={undefined} screen="direct_results" />,
    )

    clickOpens(screen.getByRole('link', { name: /대여하기/ }))

    expect(sendUsageEvent).toHaveBeenCalledExactlyOnceWith({
      type: 'library_click',
      libraryId: 12,
      place: 'result_card',
      screen: 'direct_results',
    })
  })

  it('the logo, the join link and the join guide', async () => {
    const { unmount } = render(<ResultRow row={row} library={library} searchUrl={undefined} screen="picks" />)
    expect(clickOpens(screen.getByRole('link', { name: /홈페이지/ }))).toBe(true)
    await openJoinDetails()
    // the join guide's official link reports as the old join link did (user 2026-10-19)
    expect(clickOpens(screen.getByRole('link', { name: /공식 가입 안내 보기/ }))).toBe(true)
    unmount()

    render(
      <ResultRow
        row={row}
        library={{ ...library, joinUrl: null }}
        searchUrl="https://lib.example/search?q=x"
        screen="catalog_results"
      />,
    )
    await openJoinDetails()
    // no join page: the front page in its place, as the old "전자도서관에서 가입 안내 보기"
    expect(clickOpens(screen.getByRole('link', { name: /공식 가입 안내 보기/ }))).toBe(true)

    expect(vi.mocked(sendUsageEvent).mock.calls.map(([event]) => event)).toEqual([
      { type: 'library_click', libraryId: 12, place: 'logo', screen: 'picks', bookId: 345 },
      { type: 'library_click', libraryId: 12, place: 'join_link', screen: 'picks', bookId: 345 },
      { type: 'library_click', libraryId: 12, place: 'join_guide', screen: 'catalog_results', bookId: 345 },
    ])
  })

  it('the front page of a library whose join rules were not found', async () => {
    render(
      <ResultRow
        row={row}
        library={{ ...library, joinSurvey: 'NOT_FOUND', joinUrl: null }}
        searchUrl={undefined}
        screen="book_page"
      />,
    )
    await openJoinDetails()

    expect(clickOpens(screen.getByRole('link', { name: /공식 가입 안내 보기/ }))).toBe(true)

    expect(sendUsageEvent).toHaveBeenCalledExactlyOnceWith({
      type: 'library_click',
      libraryId: 12,
      place: 'home_page',
      screen: 'book_page',
      bookId: 345,
    })
  })

  it('"이미 회원이에요 · 책 보기" in the join guide reports as the card', async () => {
    render(<ResultRow row={row} library={library} searchUrl={undefined} screen="catalog_results" />)
    await openJoinDetails()

    const link = screen.getByRole('link', { name: /이미 회원이에요/ })
    expect(link.getAttribute('href')).toBe('https://lib.example/book/1')
    expect(clickOpens(link)).toBe(true)

    expect(sendUsageEvent).toHaveBeenCalledExactlyOnceWith({
      type: 'library_click',
      libraryId: 12,
      place: 'result_card',
      screen: 'catalog_results',
      bookId: 345,
    })
  })

  it('a middle-button click opens a tab too and counts; other buttons do not', () => {
    render(<ResultRow row={row} library={library} searchUrl={undefined} screen="book_page" />)
    const rent = screen.getByRole('link', { name: /대여하기/ })

    fireEvent(rent, new MouseEvent('auxclick', { bubbles: true, button: 1 }))
    fireEvent(rent, new MouseEvent('auxclick', { bubbles: true, button: 2 }))

    expect(sendUsageEvent).toHaveBeenCalledTimes(1)
  })

  it('a click on the card itself opens nothing and sends nothing (user 2026-10-19)', () => {
    render(<ResultRow row={row} library={library} searchUrl={undefined} screen="book_page" />)
    fireEvent.click(screen.getByRole('article'))
    fireEvent.click(screen.getByRole('heading', { name: row.title }))
    expect(sendUsageEvent).not.toHaveBeenCalled()
  })

  it('without a screen nothing is sent', () => {
    render(<ResultRow row={row} library={library} searchUrl={undefined} />)

    clickOpens(screen.getByRole('link', { name: /대여하기/ }))

    expect(sendUsageEvent).not.toHaveBeenCalled()
  })
})

describe('other library links', () => {
  it('a logo on 내 도서관 sends no book', () => {
    render(
      <LibraryLogo
        name="고흥"
        logoUrl="/library-logos/12.png"
        homepageUrl="https://lib.example/"
        size={24}
        libraryId={12}
        click={{ screen: 'my_libraries' }}
      />,
    )

    expect(clickOpens(screen.getByRole('link'))).toBe(true)

    expect(sendUsageEvent).toHaveBeenCalledExactlyOnceWith({
      type: 'library_click',
      libraryId: 12,
      place: 'logo',
      screen: 'my_libraries',
    })
  })

  it('a library name in the summary bar', () => {
    render(<LibrarySummaryBar libraries={[library]} results={{}} hasMore={() => false} onMore={vi.fn()} />)

    expect(clickOpens(screen.getByRole('link', { name: /고흥군립도서관/ }))).toBe(true)

    expect(sendUsageEvent).toHaveBeenCalledExactlyOnceWith({
      type: 'library_click',
      libraryId: 12,
      place: 'summary_bar',
      screen: 'direct_results',
    })
  })
})

/** Touch screens (user 2026-10-19): every library link opens in the same tab and still reports. */
describe('on touch screens', () => {
  beforeAll(() => {
    window.matchMedia = ((query: string) => ({
      matches: query === '(pointer: coarse)',
      media: query,
      addEventListener: () => {},
      removeEventListener: () => {},
    })) as unknown as typeof window.matchMedia
  })
  afterAll(() => {
    delete (window as { matchMedia?: unknown }).matchMedia
  })

  function sameTab(link: HTMLElement): boolean {
    expect(link.hasAttribute('target')).toBe(false)
    expect(link.textContent).not.toContain('새 창')
    return fireEvent.click(link)
  }

  it('「대여하기」, the logo, and both join guide links', async () => {
    render(<ResultRow row={row} library={library} searchUrl={undefined} screen="catalog_results" />)
    expect(sameTab(screen.getByRole('link', { name: /대여하기/ }))).toBe(true)
    expect(sameTab(screen.getByRole('link', { name: /홈페이지/ }))).toBe(true)
    await openJoinDetails()
    expect(sameTab(screen.getByRole('link', { name: /이미 회원이에요/ }))).toBe(true)
    expect(sameTab(screen.getByRole('link', { name: /공식 가입 안내 보기/ }))).toBe(true)

    expect(vi.mocked(sendUsageEvent).mock.calls.map(([event]) => (event as { place?: string }).place)).toEqual([
      'result_card',
      'logo',
      'result_card',
      'join_link',
    ])
  })

  it('a library name in the summary bar', () => {
    render(<LibrarySummaryBar libraries={[library]} results={{}} hasMore={() => false} onMore={vi.fn()} />)
    expect(sameTab(screen.getByRole('link', { name: /고흥군립도서관/ }))).toBe(true)
  })
})

/** Cycle 22 (handoff 하지 않는 것): the pills and their notes send nothing; the links they lead to report as before. */
describe('pills', () => {
  it('a join pill opening the join guide sends nothing; the join link inside still reports', async () => {
    render(
      <MemoryRouter>
        <ResultRow row={row} library={{ ...library, visitRequired: true }} searchUrl={undefined} screen="catalog_results" tags />
      </MemoryRouter>,
    )
    const user = userEvent.setup()
    await user.click(screen.getByRole('button', { name: '방문 가입' }))
    expect(sendUsageEvent).not.toHaveBeenCalled()

    expect(clickOpens(screen.getByRole('link', { name: /공식 가입 안내 보기/ }))).toBe(true)
    expect(sendUsageEvent).toHaveBeenCalledExactlyOnceWith({
      type: 'library_click',
      libraryId: 12,
      place: 'join_link',
      screen: 'catalog_results',
      bookId: 345,
    })
  })
})
