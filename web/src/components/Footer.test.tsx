import { cleanup, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest'
import { AuthProvider } from '../features/auth/AuthContext'
import { Footer } from './Footer'

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

beforeAll(() => {
  document.cookie = 'XSRF-TOKEN=csrf; path=/'
})

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

function renderFooter(meStatus: number) {
  const fetchMock = vi.fn(async () => json(meStatus, { code: meStatus === 404 ? 'not_found' : 'unauthorized' }))
  vi.stubGlobal('fetch', fetchMock)
  render(
    <AuthProvider>
      <MemoryRouter>
        <Footer />
      </MemoryRouter>
    </AuthProvider>,
  )
  return fetchMock
}

const links = () => screen.getAllByRole('link').map((link) => [link.textContent, link.getAttribute('href')])

describe('footer links (cycle 16, spec R6, AC-7)', () => {
  it('login on: one line of four links', async () => {
    renderFooter(401)

    expect(await screen.findByRole('link', { name: '이용약관' })).toBeTruthy()
    expect(links()).toEqual([
      ['공지사항', '/notices'],
      ['서비스 안내', '/about'],
      ['이용약관', '/terms'],
      ['개인정보 처리방침', '/privacy'],
    ])
    expect(screen.getByRole('navigation').textContent).toBe('공지사항·서비스 안내·이용약관·개인정보 처리방침')
  })

  it('login off: only 이용약관 is left out; the privacy policy stays (ADR-0100)', async () => {
    const fetchMock = renderFooter(404)

    await waitFor(() => expect(fetchMock).toHaveBeenCalled())
    await new Promise((resolve) => setTimeout(resolve, 0))
    expect(links()).toEqual([
      ['공지사항', '/notices'],
      ['서비스 안내', '/about'],
      ['개인정보 처리방침', '/privacy'],
    ])
  })

  it('has no "문의: 준비 중" line', async () => {
    renderFooter(401)

    await screen.findByRole('link', { name: '이용약관' })
    expect(document.body.textContent).not.toContain('문의')
    expect(document.body.textContent).toContain('수량은 각 도서관의 공개 검색 결과 기준이며')
  })
})
