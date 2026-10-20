import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { ReactNode } from 'react'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router'
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest'
import { AccountSection } from '../account/AccountSection'
import { SettingsPanel } from '../search/SettingsPanel'
import { DEFAULT_SETTINGS } from '../search/searchSettings'
import { AuthProvider, useAuth } from './AuthContext'
import { AuthCompletePage } from './AuthCompletePage'
import { withdrawFailureMessage } from './authResult'
import { NoticeBar, NoticeProvider } from './NoticeContext'
import { WithdrawDialog } from './WithdrawDialog'

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
  sessionStorage.clear()
})

beforeAll(() => {
  globalThis.ResizeObserver ??= class {
    observe() {}
    unobserve() {}
    disconnect() {}
  }
  document.cookie = 'XSRF-TOKEN=csrf; path=/'
})

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

const ME = { id: 7, loginMethods: ['NAVER'], consents: [] }

function Where() {
  const location = useLocation()
  const { state } = useAuth()
  return (
    <div data-testid="where" data-auth={state.status}>
      {location.pathname + location.search}
    </div>
  )
}

function renderWith(ui: ReactNode, path: string) {
  return render(
    <QueryClientProvider client={new QueryClient()}>
      <AuthProvider>
        <NoticeProvider>
          <MemoryRouter initialEntries={[path]}>
            <NoticeBar />
            <Routes>
              <Route path="/auth/complete" element={ui} />
              <Route path="*" element={<>{ui}<Where /></>} />
            </Routes>
          </MemoryRouter>
        </NoticeProvider>
      </AuthProvider>
    </QueryClientProvider>,
  )
}

// user request 2026-10-18: 탈퇴 moved from the search settings to 마이페이지; 2026-10-19: one 마이페이지 at /my-libraries
describe('마이페이지 계정 part', () => {
  it('asks to log in before login, with no 탈퇴', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => json(401, { code: 'unauthorized' })))
    renderWith(<AccountSection />, '/my-libraries')

    expect(await screen.findByText('로그인하면 계정을 관리할 수 있어요')).toBeTruthy()
    expect(screen.getByRole('button', { name: '로그인' })).toBeTruthy()
    expect(screen.queryByRole('button', { name: '탈퇴하기' })).toBeNull()
  })

  it('holds 탈퇴하기 after login, which opens S7 with the provider in one line only', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => json(200, ME)))
    renderWith(<AccountSection />, '/my-libraries')
    const user = userEvent.setup()

    expect(await screen.findByText('로그인되어 있어요')).toBeTruthy()
    expect(screen.getByRole('region', { name: '계정' })).toBeTruthy()
    // a small link at the bottom of the 계정 part, with 로그아웃 beside the state (user 2026-10-20, ADR-0139 #9)
    const account = screen.getByRole('region', { name: '계정' })
    expect(account.contains(screen.getByRole('button', { name: '탈퇴하기' }))).toBe(true)
    expect(within(account).getByRole('button', { name: '로그아웃' })).toBeTruthy()
    // no provider name on the page itself (C23)
    expect(screen.queryByText(/네이버|카카오/)).toBeNull()
    await user.click(screen.getByRole('button', { name: '탈퇴하기' }))

    expect(await screen.findByText('되돌릴 수 없어요.')).toBeTruthy()
    // cycle 06: the saved regions and libraries go too
    expect(screen.getByText('저장한 지역과 내 도서관')).toBeTruthy()
    expect(screen.getAllByText(/네이버/)).toHaveLength(1)
    expect(screen.getByText('네이버와 chaekcheck의 연결도 끊어요.')).toBeTruthy()
  })

  it('the search settings have no 계정 row any more, signed in too', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => json(200, ME)))
    renderWith(<SettingsPanel settings={DEFAULT_SETTINGS} onChange={() => {}} />, '/search')

    await waitFor(() => expect(screen.getByTestId('where').dataset.auth).toBe('signed-in'))
    await userEvent.setup().click(screen.getByRole('button', { name: '검색 설정' }))
    expect(screen.queryByText('계정')).toBeNull()
    expect(screen.queryByRole('button', { name: /탈퇴/ })).toBeNull()
  })
})

describe('S7 withdraw dialog', () => {
  it('계속 goes to the provider re-authentication and remembers the screen', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => json(200, { ...ME, loginMethods: ['KAKAO'] })))
    const navigate = vi.fn()
    renderWith(<WithdrawDialog open onOpenChange={() => {}} provider="KAKAO" navigate={navigate} />, '/search?title=x')

    expect(screen.getByText('카카오와 chaekcheck의 연결도 끊어요.')).toBeTruthy()
    await userEvent.setup().click(screen.getByRole('button', { name: '계속' }))

    expect(navigate).toHaveBeenCalledWith('/api/v1/auth/oauth2/authorization/kakao-reauth')
    expect(sessionStorage.getItem('chaekcheck.auth.returnTo')).toBe('/search?title=x')
  })
})

describe('after re-authentication', () => {
  it('204: signed out, "탈퇴했습니다", back where the withdrawal started', async () => {
    sessionStorage.setItem('chaekcheck.auth.returnTo', '/search?title=x')
    const fetchMock = vi.fn(async (url: string, init?: RequestInit) =>
      url === '/api/v1/me' && init?.method === 'DELETE' ? new Response(null, { status: 204 }) : json(200, ME),
    )
    vi.stubGlobal('fetch', fetchMock)
    renderWith(<AuthCompletePage />, '/auth/complete?result=withdraw_reauthenticated')

    expect(await screen.findByText('탈퇴했습니다')).toBeTruthy()
    // the notice can show a moment before the move to the remembered address
    const where = await screen.findByTestId('where')
    expect(where.textContent).toBe('/search?title=x')
    expect(where.dataset.auth).toBe('signed-out')
    expect(fetchMock.mock.calls.some(([url, init]) => url === '/api/v1/me' && init?.method === 'DELETE')).toBe(true)
  })

  it.each([
    ['reauthentication_required'],
    ['reauthentication_expired'],
  ])('403 %s: the same wording, still signed in', async (code) => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async (url: string, init?: RequestInit) =>
        url === '/api/v1/me' && init?.method === 'DELETE' ? json(403, { code }) : json(200, ME),
      ),
    )
    renderWith(<AuthCompletePage />, '/auth/complete?result=withdraw_reauthenticated')

    expect(await screen.findByText('본인 확인 시간이 지났어요. 탈퇴를 다시 눌러 주세요')).toBeTruthy()
    await waitFor(() => expect(screen.getByTestId('where').dataset.auth).toBe('signed-in'))
  })

  it('500: our server failed', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async (url: string, init?: RequestInit) =>
        url === '/api/v1/me' && init?.method === 'DELETE' ? json(500, { code: 'internal_error' }) : json(200, ME),
      ),
    )
    renderWith(<AuthCompletePage />, '/auth/complete?result=withdraw_reauthenticated')

    expect(await screen.findByText('chaekcheck 서버 문제로 탈퇴하지 못했어요. 잠시 뒤 다시 해 주세요')).toBeTruthy()
  })

  it('401 and the refresh fails: signed out, "로그인이 풀렸어요"', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => json(401, { code: 'unauthorized' })))
    renderWith(<AuthCompletePage />, '/auth/complete?result=withdraw_reauthenticated')

    expect(await screen.findByText('로그인이 풀렸어요. 다시 로그인한 뒤 탈퇴해 주세요')).toBeTruthy()
    await waitFor(() => expect(screen.getByTestId('where').dataset.auth).toBe('signed-out'))
  })

  it('a network failure asks to check the connection', () => {
    expect(withdrawFailureMessage(new TypeError('Failed to fetch'))).toBe('인터넷 연결을 확인하고 다시 해 주세요')
  })
})
