import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router'
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest'
import { CONSENT_ITEMS, documentOf } from '../sitecontent/testSiteContent'
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

/** Cycle 16: the versions are version numbers ("1"), and the texts are read at those versions. */
const INFO = {
  provider: 'KAKAO',
  documents: [
    { type: 'TERMS_OF_SERVICE', version: '1' },
    { type: 'PRIVACY_COLLECTION', version: '1' },
  ],
}

const TERMS_BODY = '## 1. 서비스\n책첵은 전자도서관의 공개 검색 결과를 모아 보여 주는 무료 서비스입니다.'
const COLLECTION_BODY = '위 정보만 받으며, **탈퇴하면** 바로 지웁니다.'

/** The two documents at version 1. */
const DOCUMENTS: Record<string, () => Response> = {
  'GET /api/v1/documents/terms-of-service?version=1': () => json(200, documentOf('terms-of-service', TERMS_BODY)),
  'GET /api/v1/documents/privacy-collection?version=1': () =>
    json(200, documentOf('privacy-collection', COLLECTION_BODY, { consentItems: CONSENT_ITEMS })),
}

function Where() {
  const location = useLocation()
  return <div data-testid="where">{location.pathname}</div>
}

/** `answers` maps "METHOD path" to a function returning the response. */
function server(answers: Record<string, () => Response>) {
  const all: Record<string, () => Response> = { ...DOCUMENTS, ...answers }
  const fetchMock = vi.fn(async (url: string, init?: RequestInit) => {
    const key = `${(init?.method ?? 'GET').toUpperCase()} ${url}`
    const answer = all[key]
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
    await waitFor(() => expect(box('모두 동의').disabled).toBe(false))

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

  it('the full texts open in place, drawn as markdown', async () => {
    server({ 'GET /api/v1/auth/signup': () => json(200, INFO) })
    renderSignup()
    await screen.findByText('가입 동의')
    await waitFor(() => expect(box('모두 동의').disabled).toBe(false))
    const user = userEvent.setup()
    await waitFor(() => expect(box('이용약관').disabled).toBe(false))

    const [termsMore, collectionMore] = screen.getAllByRole('button', { name: '전문 보기' })
    await user.click(termsMore)
    expect(termsMore.getAttribute('aria-expanded')).toBe('true')
    expect(screen.getByRole('heading', { level: 2, name: '1. 서비스' })).toBeTruthy()

    await user.click(collectionMore)
    expect(screen.getByText('탈퇴하면').tagName).toBe('STRONG')
  })

  it('reads both texts at the versions the sign-up info gave', async () => {
    const fetchMock = server({ 'GET /api/v1/auth/signup': () => json(200, INFO) })
    renderSignup()

    await waitFor(() => expect(box('이용약관').disabled).toBe(false))
    const urls = fetchMock.mock.calls.map(([url]) => url)
    expect(urls).toContain('/api/v1/documents/terms-of-service?version=1')
    expect(urls).toContain('/api/v1/documents/privacy-collection?version=1')
  })

  it('the notice table is the four consent items, with no draft mark (AC-8)', async () => {
    server({ 'GET /api/v1/auth/signup': () => json(200, INFO) })
    renderSignup()

    const table = await screen.findByRole('table', { name: '개인정보 수집·이용 안내' })
    const rows = within(table).getAllByRole('row')
    expect(rows.map((row) => [row.querySelector('th')?.textContent, row.querySelector('td')?.textContent])).toEqual(
      CONSENT_ITEMS.map((item) => [item.label, item.text]),
    )
    expect(document.body.textContent).not.toContain('검토 전')
  })

  it('a text that cannot be read locks the boxes and 가입; 다시 시도 reads the same version and unlocks', async () => {
    let fails = true
    const fetchMock = server({
      'GET /api/v1/auth/signup': () => json(200, INFO),
      'GET /api/v1/documents/privacy-collection?version=1': () =>
        fails
          ? json(404, { code: 'version_not_found' })
          : json(200, documentOf('privacy-collection', COLLECTION_BODY, { consentItems: CONSENT_ITEMS })),
    })
    renderSignup()
    const user = userEvent.setup()

    expect(await screen.findByText('약관 글을 불러오지 못했어요')).toBeTruthy()
    for (const name of ['모두 동의', '이용약관', '개인정보 수집', '만 14세']) expect(box(name).disabled).toBe(true)
    expect(submitButton().disabled).toBe(true)
    // 취소 still works, and the screen stays
    expect((screen.getByRole('button', { name: '취소' }) as HTMLButtonElement).disabled).toBe(false)

    fails = false
    await user.click(screen.getByRole('button', { name: '다시 시도' }))

    await waitFor(() => expect(box('모두 동의').disabled).toBe(false))
    expect(screen.queryByText('약관 글을 불러오지 못했어요')).toBeNull()
    expect(screen.getByRole('table')).toBeTruthy()
    const collectionReads = fetchMock.mock.calls.filter(([url]) => url === '/api/v1/documents/privacy-collection?version=1')
    expect(collectionReads).toHaveLength(2)
    await user.click(box('모두 동의'))
    expect(submitButton().disabled).toBe(false)
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
    await waitFor(() => expect(box('모두 동의').disabled).toBe(false))

    await user.click(box('모두 동의'))
    await user.click(submitButton())

    await waitFor(() => expect(screen.getByTestId('where').textContent).toBe('/books/3'))
    // AC-17: the first sign-up says so once
    expect(screen.getByRole('status').textContent).toContain('가입했어요')
    const post = fetchMock.mock.calls.find(([url, init]) => url === '/api/v1/auth/signup' && init?.method === 'POST')!
    expect(JSON.parse(post[1]!.body as string)).toEqual({
      consents: [
        { type: 'TERMS_OF_SERVICE', version: '1' },
        { type: 'PRIVACY_COLLECTION', version: '1' },
        { type: 'AGE_OVER_14' },
      ],
    })
  })

  it('409: says the documents changed, shows the new versions with empty boxes, then sends the new versions', async () => {
    let version = '1'
    let posts = 0
    const fetchMock = server({
      'GET /api/v1/auth/signup': () =>
        json(200, { ...INFO, documents: [INFO.documents[0], { type: 'PRIVACY_COLLECTION', version }] }),
      'GET /api/v1/documents/privacy-collection?version=2': () =>
        json(
          200,
          documentOf('privacy-collection', '새 동의문', {
            number: 2,
            consentItems: [{ label: '목적', text: '새 목적' }, ...CONSENT_ITEMS.slice(1)],
          }),
        ),
      'POST /api/v1/auth/signup': () => {
        posts++
        if (posts === 1) {
          version = '2'
          return json(409, { code: 'document_version_mismatch' })
        }
        return new Response(null, { status: 201 })
      },
    })
    renderSignup()
    const user = userEvent.setup()
    await screen.findByText('가입 동의')
    await waitFor(() => expect(box('모두 동의').disabled).toBe(false))

    await user.click(box('모두 동의'))
    await user.click(submitButton())

    expect(await screen.findByText('약관이 바뀌었어요. 다시 확인해 주세요')).toBeTruthy()
    expect(box('이용약관').checked).toBe(false)
    expect(submitButton().disabled).toBe(true)
    // the new version's text is read and shown
    expect(await screen.findByText('새 목적')).toBeTruthy()
    expect(fetchMock.mock.calls.map(([url]) => url)).toContain('/api/v1/documents/privacy-collection?version=2')

    await user.click(box('모두 동의'))
    await user.click(submitButton())
    await waitFor(() => expect(posts).toBe(2))
    const second = fetchMock.mock.calls.filter(([url, init]) => url === '/api/v1/auth/signup' && init?.method === 'POST')[1]
    expect(JSON.parse(second[1]!.body as string).consents).toEqual([
      { type: 'TERMS_OF_SERVICE', version: '1' },
      { type: 'PRIVACY_COLLECTION', version: '2' },
      { type: 'AGE_OVER_14' },
    ])
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
    await waitFor(() => expect(box('모두 동의').disabled).toBe(false))

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
    await waitFor(() => expect(box('모두 동의').disabled).toBe(false))

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
    await waitFor(() => expect(box('모두 동의').disabled).toBe(false))

    await userEvent.setup().click(screen.getByRole('button', { name: '취소' }))

    await waitFor(() => expect(screen.getByTestId('where').textContent).toBe('/'))
    expect(fetchMock.mock.calls.some(([url, init]) => url === '/api/v1/auth/signup' && init?.method === 'DELETE')).toBe(true)
  })
})
