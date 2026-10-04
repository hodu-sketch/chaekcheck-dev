import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router'
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest'
import { AuthProvider } from './AuthContext'
import { NoticeBar, NoticeProvider } from './NoticeContext'
import { SignupPage } from './SignupPage'

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
  sessionStorage.clear()
})

beforeAll(() => {
  document.cookie = 'XSRF-TOKEN=csrf; path=/'
})

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

const INFO = {
  provider: 'KAKAO',
  documents: [
    { type: 'TERMS_OF_SERVICE', version: '2026-10-01' },
    { type: 'PRIVACY_COLLECTION', version: '2026-10-02' },
  ],
}

function Where() {
  const location = useLocation()
  return <div data-testid="where">{location.pathname}</div>
}

/** `answers` maps "METHOD path" to a function returning the response. */
function server(answers: Record<string, () => Response>) {
  const fetchMock = vi.fn(async (url: string, init?: RequestInit) => {
    const key = `${(init?.method ?? 'GET').toUpperCase()} ${url}`
    const answer = answers[key]
    if (answer) return answer()
    if (url === '/api/v1/me') return json(401, { code: 'unauthorized' })
    if (url === '/api/v1/auth/refresh') return json(401, { code: 'unauthorized' })
    throw new Error(`unexpected ${key}`)
  })
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}

function renderSignup() {
  return render(
    <QueryClientProvider client={new QueryClient()}>
      <AuthProvider>
        <NoticeProvider>
          <MemoryRouter initialEntries={['/signup']}>
            <NoticeBar />
            <Routes>
              <Route path="/signup" element={<SignupPage />} />
              <Route path="*" element={<Where />} />
            </Routes>
          </MemoryRouter>
        </NoticeProvider>
      </AuthProvider>
    </QueryClientProvider>,
  )
}

const box = (name: string) => screen.getByRole('checkbox', { name: new RegExp(name) }) as HTMLInputElement
const submitButton = () => screen.getByRole('button', { name: '가입' }) as HTMLButtonElement

describe('S6 sign-up', () => {
  it('without a pending sign-up goes to S1', async () => {
    server({ 'GET /api/v1/auth/signup': () => json(404, { code: 'signup_not_found' }) })
    renderSignup()

    expect((await screen.findByTestId('where')).textContent).toBe('/')
  })

  it('가입 is pressable only with all three boxes; 모두 동의 checks them all', async () => {
    server({ 'GET /api/v1/auth/signup': () => json(200, INFO) })
    renderSignup()
    const user = userEvent.setup()
    await screen.findByText('가입 동의')

    expect(submitButton().disabled).toBe(true)
    await user.click(box('이용약관'))
    await user.click(box('개인정보 수집'))
    expect(submitButton().disabled).toBe(true)
    // the empty one is marked
    expect(screen.getAllByText('필수 항목이에요')).toHaveLength(1)
    await user.click(box('만 14세'))
    expect(submitButton().disabled).toBe(false)

    await user.click(box('모두 동의'))
    expect([box('이용약관').checked, box('개인정보 수집').checked, box('만 14세').checked]).toEqual([false, false, false])
    await user.click(box('모두 동의'))
    expect(submitButton().disabled).toBe(false)
  })

  it('the full text opens in place', async () => {
    server({ 'GET /api/v1/auth/signup': () => json(200, INFO) })
    renderSignup()
    await screen.findByText('가입 동의')

    const more = screen.getAllByRole('button', { name: '전문 보기' })[0]
    await userEvent.setup().click(more)

    expect(more.getAttribute('aria-expanded')).toBe('true')
    expect(screen.getByText(/실제 이용약관으로 바꿉니다/)).toBeTruthy()
  })

  it('sends the versions it showed and goes back to the remembered address', async () => {
    sessionStorage.setItem('chaekcheck.auth.returnTo', '/books/3')
    const fetchMock = server({
      'GET /api/v1/auth/signup': () => json(200, INFO),
      'POST /api/v1/auth/signup': () => new Response(null, { status: 201 }),
    })
    renderSignup()
    const user = userEvent.setup()
    await screen.findByText('가입 동의')

    await user.click(box('모두 동의'))
    await user.click(submitButton())

    await waitFor(() => expect(screen.getByTestId('where').textContent).toBe('/books/3'))
    // AC-17: the first sign-up says so once
    expect(screen.getByRole('status').textContent).toContain('가입했어요')
    const post = fetchMock.mock.calls.find(([url, init]) => url === '/api/v1/auth/signup' && init?.method === 'POST')!
    expect(JSON.parse(post[1]!.body as string)).toEqual({
      consents: [
        { type: 'TERMS_OF_SERVICE', version: '2026-10-01' },
        { type: 'PRIVACY_COLLECTION', version: '2026-10-02' },
        { type: 'AGE_OVER_14' },
      ],
    })
  })

  it('409: says the documents changed, shows the new versions with empty boxes, then sends the new versions', async () => {
    let version = '2026-10-02'
    let posts = 0
    const fetchMock = server({
      'GET /api/v1/auth/signup': () =>
        json(200, { ...INFO, documents: [INFO.documents[0], { type: 'PRIVACY_COLLECTION', version }] }),
      'POST /api/v1/auth/signup': () => {
        posts++
        if (posts === 1) {
          version = '2026-11-01'
          return json(409, { code: 'document_version_mismatch' })
        }
        return new Response(null, { status: 201 })
      },
    })
    renderSignup()
    const user = userEvent.setup()
    await screen.findByText('가입 동의')

    await user.click(box('모두 동의'))
    await user.click(submitButton())

    expect(await screen.findByText('약관이 바뀌었어요. 다시 확인해 주세요')).toBeTruthy()
    expect(box('이용약관').checked).toBe(false)
    expect(submitButton().disabled).toBe(true)

    await user.click(box('모두 동의'))
    await user.click(submitButton())
    await waitFor(() => expect(posts).toBe(2))
    const second = fetchMock.mock.calls.filter(([url, init]) => url === '/api/v1/auth/signup' && init?.method === 'POST')[1]
    expect(second[1]!.body as string).toContain('2026-11-01')
  })

  it('a failed save says so and can be tried again', async () => {
    let posts = 0
    server({
      'GET /api/v1/auth/signup': () => json(200, INFO),
      'POST /api/v1/auth/signup': () => (++posts === 1 ? json(500, { code: 'internal_error' }) : new Response(null, { status: 201 })),
    })
    renderSignup()
    const user = userEvent.setup()
    await screen.findByText('가입 동의')

    await user.click(box('모두 동의'))
    await user.click(submitButton())
    expect(await screen.findByText('가입하지 못했어요. 잠시 뒤 다시 해 주세요')).toBeTruthy()

    await user.click(submitButton())
    await waitFor(() => expect(screen.getByTestId('where').textContent).toBe('/'))
  })

  it('an expired pending sign-up sends the user to S1 with the notice', async () => {
    server({
      'GET /api/v1/auth/signup': () => json(200, INFO),
      'POST /api/v1/auth/signup': () => json(404, { code: 'signup_not_found' }),
    })
    renderSignup()
    const user = userEvent.setup()
    await screen.findByText('가입 동의')

    await user.click(box('모두 동의'))
    await user.click(submitButton())

    expect(await screen.findByText('시간이 지났어요. 처음부터 다시 로그인해 주세요')).toBeTruthy()
    expect(screen.getByTestId('where').textContent).toBe('/')
  })

  it('취소 cancels the pending sign-up and goes to S1', async () => {
    const fetchMock = server({
      'GET /api/v1/auth/signup': () => json(200, INFO),
      'DELETE /api/v1/auth/signup': () => new Response(null, { status: 204 }),
    })
    renderSignup()
    await screen.findByText('가입 동의')

    await userEvent.setup().click(screen.getByRole('button', { name: '취소' }))

    await waitFor(() => expect(screen.getByTestId('where').textContent).toBe('/'))
    expect(fetchMock.mock.calls.some(([url, init]) => url === '/api/v1/auth/signup' && init?.method === 'DELETE')).toBe(true)
  })

  it('the retention period is the decided one', async () => {
    server({ 'GET /api/v1/auth/signup': () => json(200, INFO) })
    renderSignup()

    expect(await screen.findByText('탈퇴할 때까지, 또는 마지막 로그인 뒤 1년')).toBeTruthy()
  })

  it('the notice table names the 내 도서관 items and purposes (cycle 06, AC-15)', async () => {
    server({ 'GET /api/v1/auth/signup': () => json(200, INFO) })
    renderSignup()

    expect(await screen.findByText(/거주지 지역\(시군구\), 직장 지역\(시군구\), 직접 추가한 도서관\(고른 경우\)/)).toBeTruthy()
    expect(screen.getByText(/내 지역에서 가입할 수 있는 도서관을 찾아 검색에 쓰기/)).toBeTruthy()
    expect(screen.getByText(/직접 고른 도서관을 검색 결과에서 먼저 보여 주기/)).toBeTruthy()
  })
})
