import { cleanup, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest'
import type { TargetLibrary } from '../../api/types'
import { cacheAge, LibraryCountButton, LibrarySummaryBar } from './LibrarySummaryBar'
import type { LibraryResult } from './useSearch'

afterEach(cleanup)

beforeAll(() => {
  // Radix positions popovers with ResizeObserver, which jsdom does not have
  globalThis.ResizeObserver ??= class {
    observe() {}
    unobserve() {}
    disconnect() {}
  }
})

const library = (id: number): TargetLibrary => ({
  id,
  name: `도서관${id}`,
  libraryType: 'EBOOK',
  platformType: 'KYOBO_V1',
  priorityGroup: 'OPEN',
  deferred: false,
  extraConditionText: null,
  visitRequired: false,
  eligibility: 'OPEN',
  eligibilitySummary: null,
  joinMethod: '가입',
  joinUrl: null,
  joinCaution: null,
  joinNotice: null,
  homepageUrl: null,
  chosen: false,
  joinSurvey: 'SURVEYED',
  logoUrl: null,
  mobileSiteDiffers: false,
})

const loading: LibraryResult = { status: 'loading', pages: [], loanRate: null, more: 'idle' }

describe('LibrarySummaryBar (U-8)', () => {
  it('shows every library when there are 5 or fewer', () => {
    const libraries = [1, 2, 3, 4, 5].map(library)
    render(<LibrarySummaryBar libraries={libraries} results={{}} hasMore={() => false} onMore={vi.fn()} />)

    expect(screen.getAllByText(/^도서관\d$/)).toHaveLength(5)
    expect(screen.queryByRole('button', { name: /모두 보기/ })).toBeNull()
  })

  it('collapses the rest into "외 N곳" and lists all libraries when clicked', async () => {
    const user = userEvent.setup()
    const libraries = [1, 2, 3, 4, 5, 6, 7].map(library)
    const results = Object.fromEntries(libraries.map((l) => [l.id, loading]))
    render(<LibrarySummaryBar libraries={libraries} results={results} hasMore={() => false} onMore={vi.fn()} />)

    const bar = screen.getByRole('list', { name: '조회한 도서관' })
    expect(within(bar).getAllByText(/^도서관\d$/)).toHaveLength(5)
    const overflow = screen.getByRole('button', { name: '도서관 7곳 모두 보기' })
    expect(overflow.textContent).toContain('외 2곳')

    await user.click(overflow)

    const dialog = await screen.findByRole('dialog')
    expect(within(dialog).getByText('조회한 도서관 7곳')).toBeTruthy()
    expect(within(dialog).getAllByText(/^도서관\d$/)).toHaveLength(7)
  })
})

describe('LibraryCountButton (phones, user request 2026-10-05)', () => {
  it('is one "N곳" button, no row of libraries, and opens the full list', async () => {
    const user = userEvent.setup()
    const libraries = [1, 2, 3].map(library)
    render(<LibraryCountButton libraries={libraries} results={{}} hasMore={() => false} onMore={vi.fn()} />)

    expect(screen.queryByRole('list', { name: '조회한 도서관' })).toBeNull()
    const button = screen.getByRole('button', { name: '도서관 3곳 모두 보기' })
    expect(button.textContent).toBe('3곳')

    await user.click(button)

    const dialog = await screen.findByRole('dialog')
    expect(within(dialog).getByText('조회한 도서관 3곳')).toBeTruthy()
    expect(within(dialog).getAllByText(/^도서관\d$/)).toHaveLength(3)
  })
})

describe('LibrarySummaryBar front page link', () => {
  it('links the library name to its front page in a new tab, and shows plain text without one', () => {
    const linked = { ...library(1), homepageUrl: 'https://www.ghlib.go.kr:446/elibrary-front/main.ink' }
    render(<LibrarySummaryBar libraries={[linked, library(2)]} results={{}} hasMore={() => false} onMore={vi.fn()} />)

    const link = screen.getByRole('link', { name: /도서관1/ })
    expect(link.getAttribute('href')).toBe('https://www.ghlib.go.kr:446/elibrary-front/main.ink')
    expect(link.getAttribute('target')).toBe('_blank')
    expect(screen.queryByRole('link', { name: /도서관2/ })).toBeNull()
  })
})

describe('cacheAge', () => {
  it('reads "방금 조회" under a minute, then whole minutes', () => {
    expect(cacheAge(59)).toBe('방금 조회')
    expect(cacheAge(60)).toBe('1분 전 조회')
    expect(cacheAge(179)).toBe('2분 전 조회')
  })
})
