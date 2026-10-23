import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { AppLayout } from './AppLayout'

vi.mock('../features/auth/AuthHeaderAction', () => ({ AuthHeaderAction: () => <button type="button">로그인</button> }))
vi.mock('../features/auth/NoticeContext', () => ({ NoticeBar: () => null }))
vi.mock('../features/mylibrary/RegionImportDialog', () => ({ RegionImportDialog: () => null }))

beforeEach(() => {
  vi.stubGlobal('fetch', vi.fn(() => new Promise<Response>(() => {})))
})

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

function Where() {
  return <p data-testid="where">{useLocation().pathname}</p>
}

function renderAt(path: string) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route element={<AppLayout />}>
            <Route path="*" element={<Where />} />
          </Route>
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

function wide() {
  return screen.getAllByRole('navigation', { name: '주 메뉴' })[0]
}

/** Cycle 21 spec R1 (ADR-0127); plain menus without sub-menus (user 2026-10-19); cycle 14 AC-15 (no tagline). */
describe('header', () => {
  it('has the three menus as plain links, no tagline and no sub-menus', () => {
    renderAt('/')
    const nav = within(wide())
    expect(nav.getAllByRole('link').map((a) => [a.textContent, a.getAttribute('href')])).toEqual([
      ['소개', '/about'],
      ['도서검색', '/search'],
      ['추천도서', '/picks'],
      ['마이페이지', '/mypage'],
    ])
    expect(screen.getByRole('button', { name: '로그인' })).toBeTruthy()
    expect(screen.queryByRole('link', { name: '이동진 추천도서' })).toBeNull()
    expect(screen.queryByRole('link', { name: '계정' })).toBeNull()
    expect(screen.queryByText('전자도서관 대출 가능 확인')).toBeNull()
    expect(screen.queryByText(/인기 도서/)).toBeNull()
  })

  it('goes on a click', () => {
    renderAt('/')
    fireEvent.click(within(wide()).getByRole('link', { name: '마이페이지' }))
    expect(screen.getByTestId('where').textContent).toBe('/mypage')
  })

  it('goes to the main page on the logo', () => { // user 2026-10-16
    renderAt('/picks/snu100')
    fireEvent.click(screen.getByRole('link', { name: '책첵' }))
    expect(screen.getByTestId('where').textContent).toBe('/')
    expect(screen.queryByTestId('menu-dim')).toBeNull()
  })

  it('has 소개 as the first menu, going to /about (user 2026-10-24)', () => {
    renderAt('/')
    const about = within(wide()).getAllByRole('link')[0]
    expect(about.textContent).toBe('소개')
    expect(about.getAttribute('href')).toBe('/about')
    expect(about.hasAttribute('data-current')).toBe(false)
    fireEvent.click(about)
    expect(screen.getByTestId('where').textContent).toBe('/about')
    expect(within(wide()).getByRole('link', { name: '소개' }).hasAttribute('data-current')).toBe(true)
    expect(within(wide()).getByRole('link', { name: '소개' }).getAttribute('aria-current')).toBe('page')
  })

  it.each([
    ['/about', '소개'],
    ['/search', '도서검색'],
    ['/books/1', '도서검색'],
    ['/picks/snu100', '추천도서'],
    ['/picks', '추천도서'],
    ['/mypage', '마이페이지'],
  ])('underlines the menu of %s', (path, label) => {
    renderAt(path)
    const current = within(wide())
      .getAllByRole('link')
      .filter((a) => a.hasAttribute('data-current'))
      .map((a) => a.textContent)
    expect(current).toEqual([label])
    expect(within(wide()).getByRole('link', { name: label }).getAttribute('aria-current')).toBe('page')
  })

  it('marks no menu on the main page (user 2026-10-20)', () => {
    renderAt('/')
    expect(within(wide()).getAllByRole('link').filter((a) => a.hasAttribute('data-current'))).toEqual([])
    expect(within(wide()).queryByRole('link', { current: 'page' })).toBeNull()
  })

  it.each(['/terms', '/privacy'])('is the same header on %s', (path) => { // AC-3; cycle 20 AC-1 (ADR-0121)
    renderAt(path)
    expect(within(wide()).getByRole('link', { name: '추천도서' })).toBeTruthy()
    expect(screen.getByRole('button', { name: '로그인' })).toBeTruthy()
    expect(screen.getByRole('button', { name: '메뉴 열기' })).toBeTruthy()
    expect(screen.getByRole('link', { name: '책첵' })).toBeTruthy()
  })

  it('has no lookup status light and asks no status (user 2026-10-19)', () => {
    const { container } = renderAt('/')
    expect(container.querySelector('[data-lookup-status-slot]')).toBeNull()
    expect(screen.queryByRole('button', { name: /조회 상태/ })).toBeNull()
    expect(vi.mocked(fetch).mock.calls.some(([url]) => String(url).includes('lookup-status'))).toBe(false)
  })
})

/**
 * Phones (user 2026-10-19): a ☰ button at the far left, before the logo, in place of the ⌄/⌃ row; the same three
 * menus with the screen darkened; the button again (✕), the dark part, Esc or a menu closes them.
 */
describe('phone menu', () => {
  function openPhone() {
    fireEvent.click(screen.getByRole('button', { name: '메뉴 열기' }))
    return screen.getAllByRole('navigation', { name: '주 메뉴' })[1]
  }

  it('is a ☰ icon button before the logo, with no chevron', () => {
    renderAt('/')
    const button = screen.getByRole('button', { name: '메뉴 열기' })
    const logo = screen.getByRole('link', { name: '책첵' })
    expect(button.getAttribute('aria-expanded')).toBe('false')
    expect(button.querySelector('svg')).toBeTruthy()
    expect(button.compareDocumentPosition(logo) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    // the 책첵이란? link after the logo (the wide menus between are hidden on phones), 로그인 at the end
    const about = screen.getByRole('link', { name: '소개' })
    expect(logo.compareDocumentPosition(about) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    const login = screen.getByRole('button', { name: '로그인' })
    expect(logo.compareDocumentPosition(login) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
  })

  it('opens the same three menus and closes from the same button (✕)', () => {
    renderAt('/')
    const drawer = openPhone()
    expect(within(drawer).getAllByRole('link').map((a) => a.textContent)).toEqual(['소개', '도서검색', '추천도서', '마이페이지'])
    expect(screen.getByTestId('menu-dim')).toBeTruthy()
    const close = screen.getByRole('button', { name: '메뉴 닫기' })
    expect(close.getAttribute('aria-expanded')).toBe('true')
    fireEvent.click(close)
    expect(screen.queryByTestId('menu-dim')).toBeNull()
  })

  it('closes on the dark part and on Esc', () => {
    renderAt('/')
    openPhone()
    fireEvent.click(screen.getByTestId('menu-dim'))
    expect(screen.getAllByRole('navigation', { name: '주 메뉴' })).toHaveLength(1)
    openPhone()
    fireEvent.keyDown(document, { key: 'Escape' })
    expect(screen.getAllByRole('navigation', { name: '주 메뉴' })).toHaveLength(1)
  })

  it('goes and closes on a menu, with the menu on screen marked', () => {
    renderAt('/picks/librarian')
    const drawer = openPhone()
    expect(within(drawer).getByRole('link', { name: '추천도서' }).getAttribute('aria-current')).toBe('page')
    fireEvent.click(within(drawer).getByRole('link', { name: '마이페이지' }))
    expect(screen.getByTestId('where').textContent).toBe('/mypage')
    expect(screen.queryByTestId('menu-dim')).toBeNull()
  })

  it('has 소개 first in the drawer too, going to /about (user 2026-10-24)', () => {
    renderAt('/')
    const drawer = openPhone()
    const about = within(drawer).getAllByRole('link')[0]
    expect(about.textContent).toBe('소개')
    expect(about.getAttribute('href')).toBe('/about')
  })
})
