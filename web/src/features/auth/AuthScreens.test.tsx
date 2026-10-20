import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState, type ReactNode } from 'react'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router'
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest'
import { AuthProvider } from './AuthContext'
import { AuthCompletePage } from './AuthCompletePage'
import { AuthHeaderAction } from './AuthHeaderAction'
import { LoginDialog } from './LoginDialog'
import { NoticeBar, NoticeProvider } from './NoticeContext'

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

const ME = { id: 7, loginMethods: ['KAKAO'], consents: [{ type: 'AGE_OVER_14', agreedAt: '2026-10-04T00:00:00Z' }] }

function Where() {
  const location = useLocation()
  return <div data-testid="where">{location.pathname + location.search}</div>
}

function renderWith(ui: ReactNode, path = '/search?title=a') {
  return render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <AuthProvider>
        <NoticeProvider>
          <MemoryRouter initialEntries={[path]}>
            <NoticeBar />
            <Routes>
              <Route path="*" element={<>{ui}<Where /></>} />
            </Routes>
          </MemoryRouter>
        </NoticeProvider>
      </AuthProvider>
    </QueryClientProvider>,
  )
}

describe('header login area', () => {
  it('stays empty while the first check runs', () => {
    vi.stubGlobal('fetch', vi.fn(() => new Promise<Response>(() => {})))
    renderWith(<AuthHeaderAction />)

    expect(screen.queryByRole('button')).toBeNull()
  })

  it('shows nothing when login is off', async () => {
    const fetchMock = vi.fn(async () => json(404, { code: 'not_found' }))
    vi.stubGlobal('fetch', fetchMock)
    renderWith(<AuthHeaderAction />)

    await waitFor(() => expect(fetchMock).toHaveBeenCalled())
    await new Promise((resolve) => setTimeout(resolve, 0))
    expect(screen.queryByRole('button')).toBeNull()
  })

  it('shows 로그인 before login and 로그아웃 after, without provider names', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => json(200, ME)))
    renderWith(<AuthHeaderAction />)

    expect(await screen.findByRole('button', { name: '로그아웃' })).toBeTruthy()
    expect(document.body.textContent).not.toMatch(/카카오|네이버|KAKAO|NAVER/)
  })

  it('logout switches the button back to 로그인 and stays on the screen', async () => {
    const fetchMock = vi.fn(async (url: string) =>
      url === '/api/v1/auth/logout' ? new Response(null, { status: 204 }) : json(200, ME),
    )
    vi.stubGlobal('fetch', fetchMock)
    renderWith(<AuthHeaderAction />)

    await userEvent.setup().click(await screen.findByRole('button', { name: '로그아웃' }))

    expect(await screen.findByRole('button', { name: '로그인' })).toBeTruthy()
    expect(screen.getByTestId('where').textContent).toBe('/search?title=a')
  })
})

describe('S5 login dialog', () => {
  it('shows only the providers the server offers and remembers where the user was', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async (url: string) => (url === '/api/v1/auth/providers' ? json(200, ['KAKAO']) : json(401, {}))),
    )
    const navigate = vi.fn()
    renderWith(<LoginDialog open onOpenChange={() => {}} navigate={navigate} />)

    const kakao = await screen.findByRole('button', { name: '카카오로 계속하기' })
    expect(screen.queryByRole('button', { name: '네이버 로그인' })).toBeNull()
    expect(screen.getByText('처음 이용하면 책첵 계정 가입으로 이어져요.')).toBeTruthy()

    await userEvent.setup().click(kakao)

    expect(navigate).toHaveBeenCalledWith('/api/v1/auth/oauth2/authorization/kakao')
    expect(sessionStorage.getItem('chaekcheck.auth.returnTo')).toBe('/search?title=a')
    expect((kakao as HTMLButtonElement).disabled).toBe(true)
  })

  it('shows both buttons by default', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async (url: string) =>
        url === '/api/v1/auth/providers' ? json(200, ['NAVER', 'KAKAO']) : json(401, {}),
      ),
    )
    renderWith(<LoginDialog open onOpenChange={() => {}} navigate={vi.fn()} />)

    const naver = await screen.findByRole('button', { name: '네이버 로그인' })
    const kakao = screen.getByRole('button', { name: '카카오로 계속하기' })
    // Kakao first, Naver under it (user 2026-10-19)
    expect(kakao.compareDocumentPosition(naver) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
  })

  it('is the redesigned window: eyebrow, headline, benefits, the membership note and × (user 2026-10-19)', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async (url: string) => (url === '/api/v1/auth/providers' ? json(200, ['KAKAO']) : json(401, {}))),
    )
    renderWith(<LoginDialog open onOpenChange={() => {}} navigate={vi.fn()} />)
    const dialog = screen.getByRole('dialog', { name: '책첵 로그인' })
    expect(within(dialog).getByRole('heading', { name: /내 검색 설정을\s*다른 기기에서도 그대로\./ })).toBeTruthy()
    expect(
      within(dialog).getByText(
        '로그인하면 거주·재직 지역과 직접 추가한 도서관이 계정에 저장돼 폰과 PC에서 똑같이 보여요. 로그인하지 않아도 모든 기능을 쓸 수 있어요.',
      ),
    ).toBeTruthy()
    expect(within(dialog).getAllByRole('listitem').map((item) => item.textContent)).toEqual([
      '내가 정한 검색 범위 저장',
      'PC와 휴대폰에서 같은 설정 사용',
    ])
    expect(within(dialog).getByText('도서관 회원가입과는 별개예요.')).toBeTruthy()
    expect(within(dialog).getByText('전자책을 빌리려면 각 도서관의 가입·인증이 필요해요.')).toBeTruthy()
    expect(within(dialog).getByRole('button', { name: '닫기' })).toBeTruthy()
    expect(await within(dialog).findByRole('button', { name: '카카오로 계속하기' })).toBeTruthy()
  })

  it('"로그인 없이 계속하기" closes the window', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async (url: string) => (url === '/api/v1/auth/providers' ? json(200, ['KAKAO']) : json(401, {}))),
    )
    const onOpenChange = vi.fn()
    const navigate = vi.fn()
    renderWith(<LoginDialog open onOpenChange={onOpenChange} navigate={navigate} />)
    await userEvent.setup().click(screen.getByRole('button', { name: '로그인 없이 계속하기' }))
    expect(onOpenChange).toHaveBeenCalledWith(false)
    expect(navigate).not.toHaveBeenCalled()
  })

  it('says so when the providers cannot be read', async () => {
    vi.stubGlobal('fetch', vi.fn(async (url: string) => (url === '/api/v1/auth/providers' ? json(500, {}) : json(401, {}))))
    renderWith(<LoginDialog open onOpenChange={() => {}} navigate={vi.fn()} />)
    expect(await screen.findByText('지금은 로그인할 수 없어요. 잠시 뒤 다시 해 주세요')).toBeTruthy()
    expect(screen.queryByRole('button', { name: '카카오로 계속하기' })).toBeNull()
  })
})

/** Phones (2026-10-12, "로그인 창도 이 다섯 개처럼 손잡이와 ⓧ를 붙여줘"): the shared sheet with a handle and ⓧ. */
describe('S5 login sheet on phones', () => {
  function LoginSheet() {
    const [open, setOpen] = useState(true)
    return <LoginDialog open={open} onOpenChange={setOpen} navigate={vi.fn()} />
  }

  it('has a handle and ⓧ, no separate 닫기, and ⓧ or back closes only the sheet', async () => {
    vi.stubGlobal('matchMedia', (query: string) => ({
      matches: query.includes('640px'),
      media: query,
      addEventListener() {},
      removeEventListener() {},
    }))
    vi.stubGlobal(
      'fetch',
      vi.fn(async (url: string) => (url === '/api/v1/auth/providers' ? json(200, ['KAKAO']) : json(401, {}))),
    )
    renderWith(<LoginSheet />)
    const sheet = screen.getByRole('dialog', { name: '책첵 로그인' })
    expect(within(sheet).getByTestId('sheet-handle')).toBeTruthy()
    expect(await within(sheet).findByRole('button', { name: '카카오로 계속하기' })).toBeTruthy()
    expect(within(sheet).getByRole('button', { name: '로그인 없이 계속하기' })).toBeTruthy()
    expect(within(sheet).queryByRole('button', { name: '닫기' })).toBeTruthy()
    expect(within(sheet).queryByText('닫기')).toBeNull()

    await waitFor(() => expect(window.history.state).toHaveProperty('chaekcheckSheet'))
    window.history.back()
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
  })
})

describe('/auth/complete', () => {
  it('login_success goes back to the remembered address', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => json(200, ME)))
    sessionStorage.setItem('chaekcheck.auth.returnTo', '/books/12')
    renderWith(<AuthCompletePage />, '/auth/complete?result=login_success')

    await waitFor(() => expect(screen.getByTestId('where').textContent).toBe('/books/12'))
  })

  it('a remembered address outside the site is not followed', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => json(200, ME)))
    sessionStorage.setItem('chaekcheck.auth.returnTo', '//evil.example')
    renderWith(<AuthCompletePage />, '/auth/complete?result=login_success')

    await waitFor(() => expect(screen.getByTestId('where').textContent).toBe('/'))
  })

  it('a failure shows its notice on S1 and the code leaves the address', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => json(401, {})))
    renderWith(<AuthCompletePage />, '/auth/complete?result=login_provider_error&provider=naver')

    expect(await screen.findByText('네이버 쪽 문제로 로그인하지 못했어요. 잠시 뒤 다시 해 주세요')).toBeTruthy()
    expect(screen.getByTestId('where').textContent).toBe('/')
  })

  it('an unknown code goes to S1 without a notice', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => json(401, {})))
    renderWith(<AuthCompletePage />, '/auth/complete?result=something_else')

    await waitFor(() => expect(screen.getByTestId('where').textContent).toBe('/'))
    expect(screen.queryByRole('status')).toBeNull()
    expect(document.body.textContent).not.toContain('something_else')
  })

  it('the notice can be closed', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => json(401, {})))
    renderWith(<AuthCompletePage />, '/auth/complete?result=login_cancelled')

    await screen.findByText('로그인을 취소했어요')
    await userEvent.setup().click(screen.getByRole('button', { name: '안내 닫기' }))
    expect(screen.queryByText('로그인을 취소했어요')).toBeNull()
  })
})
