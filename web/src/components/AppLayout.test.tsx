import { cleanup, render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { AppLayout } from './AppLayout'

vi.mock('../features/auth/AuthHeaderAction', () => ({ AuthHeaderAction: () => <button type="button">로그인</button> }))
vi.mock('../features/auth/NoticeContext', () => ({ NoticeBar: () => null }))
vi.mock('../features/mylibrary/RegionImportDialog', () => ({ RegionImportDialog: () => null }))

afterEach(cleanup)

function renderAt(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route element={<AppLayout />}>
          <Route path="*" element={<p>화면</p>} />
        </Route>
      </Routes>
    </MemoryRouter>,
  )
}

/** Cycle 10 (flow.md 머리, AC-15): "인기 도서" in the header, no tagline. */
describe('header', () => {
  it('has the 인기 도서 link next to the logo and no tagline', () => {
    renderAt('/')
    const link = screen.getByRole('link', { name: '인기 도서' })
    expect(link.getAttribute('href')).toBe('/popular')
    expect(screen.getByRole('button', { name: '로그인' })).toBeTruthy()
    expect(screen.queryByText('전자도서관 대출 가능 확인')).toBeNull()
  })

  it('marks the link on the popular screen', () => {
    renderAt('/popular')
    expect(screen.getByRole('link', { name: '인기 도서' }).getAttribute('aria-current')).toBe('page')
  })

  it.each(['/terms', '/privacy'])('leaves the link and login out on %s', (path) => {
    renderAt(path)
    expect(screen.queryByRole('link', { name: '인기 도서' })).toBeNull()
    expect(screen.queryByRole('button', { name: '로그인' })).toBeNull()
    expect(screen.getByRole('link', { name: '책첵' })).toBeTruthy()
  })
})
