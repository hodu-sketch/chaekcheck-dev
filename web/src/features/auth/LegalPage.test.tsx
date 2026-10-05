import { cleanup, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest'
import { Footer } from '../../components/Footer'
import { AuthProvider } from './AuthContext'
import documentVersions from './documentVersions.json'
import { LegalPage } from './LegalPage'
import { LoginOnly } from './LoginOnly'

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

function renderWithLogin(status: number, ui: React.ReactNode) {
  vi.stubGlobal('fetch', vi.fn(async () => json(status, { code: 'x' })))
  return render(
    <AuthProvider>
      <MemoryRouter>{ui}</MemoryRouter>
    </AuthProvider>,
  )
}

describe('S8 terms and S9 privacy', () => {
  it('open without login and show the version and effective date', async () => {
    renderWithLogin(401, (
      <LoginOnly>
        <LegalPage document="terms" />
      </LoginOnly>
    ))

    expect(await screen.findByRole('heading', { name: '이용약관' })).toBeTruthy()
    const version = documentVersions.termsOfService
    expect(document.body.textContent).toContain(`버전 ${version} · 시행일 ${version}`)
    expect(screen.getByText('검토 전 문구')).toBeTruthy()
  })

  it('the privacy policy has the sections the law requires', async () => {
    renderWithLogin(401, <LegalPage document="privacy" />)

    for (const heading of ['보유·이용 기간', '파기 절차와 방법', '정보주체의 권리와 행사 방법', '개인정보 보호책임자']) {
      expect(screen.getByRole('heading', { name: heading })).toBeTruthy()
    }
    expect(screen.getByText(/탈퇴할 때까지, 또는 마지막 로그인 뒤 1년/)).toBeTruthy()
  })

  it('names the 내 도서관 items, their purposes and how to remove them (cycle 06, AC-15)', async () => {
    renderWithLogin(401, <LegalPage document="privacy" />)

    expect(screen.getByText(/거주지 지역\(시군구\)과 직장 지역\(시군구\)을 내 지역에서 가입할 수 있는 도서관을 찾아/)).toBeTruthy()
    expect(screen.getByText(/직접 추가한 도서관\(고른 경우\)을 직접 고른 도서관을 검색 결과에서 먼저 보여 주는/)).toBeTruthy()
    expect(screen.getByText(/"내 도서관" 화면에서 언제든 비우거나 지울 수 있습니다/)).toBeTruthy()
  })

  it('are not drawn while login is off', async () => {
    const fetchMock = vi.fn(async () => json(404, { code: 'not_found' }))
    vi.stubGlobal('fetch', fetchMock)
    render(
      <AuthProvider>
        <MemoryRouter>
          <LoginOnly>
            <LegalPage document="privacy" />
          </LoginOnly>
        </MemoryRouter>
      </AuthProvider>,
    )

    await waitFor(() => expect(fetchMock).toHaveBeenCalled())
    await waitFor(() => expect(screen.queryByRole('heading', { name: '개인정보 처리방침' })).toBeNull())
  })
})

describe('footer links', () => {
  it('show both links while login is on', async () => {
    renderWithLogin(401, <Footer />)

    expect((await screen.findByRole('link', { name: '이용약관' })).getAttribute('href')).toBe('/terms')
    expect(screen.getByRole('link', { name: '개인정보 처리방침' }).getAttribute('href')).toBe('/privacy')
    expect(screen.getByRole('link', { name: '서비스 안내' }).getAttribute('href')).toBe('/about')
  })

  it('show only "서비스 안내" while login is off (cycle 07, AC-21)', async () => {
    const fetchMock = vi.fn(async () => json(404, { code: 'not_found' }))
    vi.stubGlobal('fetch', fetchMock)
    render(
      <AuthProvider>
        <MemoryRouter>
          <Footer />
        </MemoryRouter>
      </AuthProvider>,
    )

    await waitFor(() => expect(fetchMock).toHaveBeenCalled())
    await new Promise((resolve) => setTimeout(resolve, 0))
    expect(screen.getAllByRole('link').map((link) => link.getAttribute('href'))).toEqual(['/about'])
  })
})
