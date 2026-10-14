import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router'
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest'
import { calledUrls, documentOf, fakeServer, json, problem, TWO_VERSIONS } from '../sitecontent/testSiteContent'
import { AuthProvider } from './AuthContext'
import { LegalPage } from './LegalPage'
import { LoginOnly } from './LoginOnly'

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

beforeAll(() => {
  document.cookie = 'XSRF-TOKEN=csrf; path=/'
})

const TERMS = documentOf('terms-of-service', '## 1. 서비스\n책첵은 전자도서관의 공개 검색 결과를 모아 보여 주는 무료 서비스입니다.')
const PRIVACY = documentOf(
  'privacy-policy',
  '## 3. 쿠키\n로그인 유지와 로그인 상태 확인용 쿠키를 씁니다.\n\n## 4. 권리, 안전 조치, 문의\n- 개인정보 보호 담당: 책첵 운영자, <chaekcheck9104@gmail.com>',
)

function Where() {
  const location = useLocation()
  return <div data-testid="where">{location.pathname + location.search}</div>
}

/** The two routes as `main.tsx` has them: /terms only while login is on, /privacy always (ADR-0100). */
function renderAt(path: string) {
  return render(
    <QueryClientProvider client={new QueryClient()}>
      <AuthProvider>
        <MemoryRouter initialEntries={[path]}>
          <Routes>
            <Route
              path="/terms"
              element={
                <LoginOnly>
                  <LegalPage document="terms" />
                </LoginOnly>
              }
            />
            <Route path="/privacy" element={<LegalPage document="privacy" />} />
          </Routes>
          <Where />
        </MemoryRouter>
      </AuthProvider>
    </QueryClientProvider>,
  )
}

describe('S8 terms and S9 privacy (cycle 16)', () => {
  it('open without login and show "시행 YYYY-MM-DD" and the text from the API', async () => {
    const fetchMock = fakeServer({ '/api/v1/documents/terms-of-service': () => json(200, TERMS) })
    renderAt('/terms')

    expect(await screen.findByRole('heading', { level: 1, name: '이용약관' })).toBeTruthy()
    expect(await screen.findByText('시행 2026-10-04')).toBeTruthy()
    expect(screen.getByRole('heading', { level: 2, name: '1. 서비스' })).toBeTruthy()
    expect(screen.getByText(/공개 검색 결과를 모아 보여 주는 무료 서비스/)).toBeTruthy()
    // one version: no picker, no change summary
    expect(screen.queryByRole('combobox')).toBeNull()
    expect(screen.queryByRole('button', { name: '바뀐 점 보기' })).toBeNull()
    expect(calledUrls(fetchMock)).toContain('/api/v1/documents/terms-of-service')
  })

  it('has no draft mark (AC-8)', async () => {
    fakeServer({
      '/api/v1/documents/terms-of-service': () => json(200, TERMS),
      '/api/v1/documents/privacy-policy': () => json(200, PRIVACY),
    })
    renderAt('/terms')
    await screen.findByText('시행 2026-10-04')
    expect(document.body.textContent).not.toContain('검토 전')
    cleanup()

    renderAt('/privacy')
    await screen.findByText('시행 2026-10-04')
    expect(document.body.textContent).not.toContain('검토 전')
  })

  it('the privacy policy is read from privacy-policy and its e-mail is a mailto link', async () => {
    fakeServer({ '/api/v1/documents/privacy-policy': () => json(200, PRIVACY) })
    renderAt('/privacy')

    expect(await screen.findByRole('heading', { level: 2, name: '3. 쿠키' })).toBeTruthy()
    expect(screen.getByRole('link', { name: 'chaekcheck9104@gmail.com' }).getAttribute('href')).toBe(
      'mailto:chaekcheck9104@gmail.com',
    )
  })

  it('"바뀐 점 보기" opens the change summary of a version that has one (AC-11)', async () => {
    fakeServer({
      '/api/v1/documents/privacy-policy': () =>
        json(
          200,
          documentOf(
            'privacy-policy',
            '## 1. 수집 항목\n바뀐 글',
            { number: 2, effectiveOn: '2026-10-20', changeSummary: '- 접속 기록 보유 기간을 **14일**로 적었어요' },
            TWO_VERSIONS,
          ),
        ),
    })
    renderAt('/privacy')

    const toggle = await screen.findByRole('button', { name: '바뀐 점 보기' })
    expect(toggle.getAttribute('aria-expanded')).toBe('false')
    await userEvent.setup().click(toggle)
    expect(toggle.getAttribute('aria-expanded')).toBe('true')
    expect(screen.getByText('14일').tagName).toBe('STRONG')
  })

  it('an earlier version from the picker: its text, its date and the small "이전 버전" mark (AC-2)', async () => {
    const fetchMock = fakeServer({
      '/api/v1/documents/privacy-policy': () =>
        json(200, documentOf('privacy-policy', '지금 글', { number: 2, effectiveOn: '2026-10-20' }, TWO_VERSIONS)),
      '/api/v1/documents/privacy-policy?version=1': () =>
        json(200, documentOf('privacy-policy', '옛 글', { number: 1, current: false }, TWO_VERSIONS)),
    })
    renderAt('/privacy')
    const user = userEvent.setup()

    const picker = (await screen.findByRole('combobox', { name: '적용 기간' })) as HTMLSelectElement
    expect([...picker.options].map((option) => option.textContent)).toEqual([
      '2026-10-20 ~ 지금',
      '2026-10-04 ~ 2026-10-19 적용',
    ])
    expect(screen.getByText('시행 2026-10-20')).toBeTruthy()
    expect(screen.queryByText('이전 버전')).toBeNull()

    await user.selectOptions(picker, '1')
    expect(await screen.findByText('옛 글')).toBeTruthy()
    expect(screen.getByText('시행 2026-10-04')).toBeTruthy()
    expect(screen.getByText('이전 버전')).toBeTruthy()
    expect(screen.getByTestId('where').textContent).toBe('/privacy?version=1')
    expect(calledUrls(fetchMock)).toContain('/api/v1/documents/privacy-policy?version=1')

    await user.selectOptions(screen.getByRole('combobox'), '2')
    expect(await screen.findByText('지금 글')).toBeTruthy()
    expect(screen.getByTestId('where').textContent).toBe('/privacy')
  })

  it('a version that does not exist is "찾을 수 없어요" with a link to the current version (AC-9)', async () => {
    fakeServer({ '/api/v1/documents/terms-of-service?version=99': () => problem(404, 'version_not_found') })
    renderAt('/terms?version=99')

    expect(await screen.findByText('찾을 수 없어요')).toBeTruthy()
    expect(screen.getByRole('link', { name: '지금 버전 보기' }).getAttribute('href')).toBe('/terms')
  })

  it('shows grey lines while loading and 다시 시도 after a server error', async () => {
    let calls = 0
    fakeServer({
      '/api/v1/documents/privacy-policy': () => (++calls <= 2 ? problem(500, 'internal_error') : json(200, PRIVACY)),
    })
    renderAt('/privacy')

    expect(screen.getByRole('status', { name: '불러오는 중' })).toBeTruthy()
    expect(await screen.findByText('글을 불러오지 못했어요', undefined, { timeout: 3000 })).toBeTruthy()
    await userEvent.setup().click(screen.getByRole('button', { name: '다시 시도' }))
    expect(await screen.findByRole('heading', { level: 2, name: '3. 쿠키' })).toBeTruthy()
  })

  it('with login off /privacy opens and /terms is not drawn (AC-7, ADR-0100)', async () => {
    const fetchMock = fakeServer({
      '/api/v1/me': () => problem(404, 'not_found'),
      '/api/v1/documents/privacy-policy': () => json(200, PRIVACY),
      '/api/v1/documents/terms-of-service': () => json(200, TERMS),
    })
    renderAt('/privacy')
    expect(await screen.findByRole('heading', { level: 1, name: '개인정보 처리방침' })).toBeTruthy()
    expect(await screen.findByText('시행 2026-10-04')).toBeTruthy()
    cleanup()

    renderAt('/terms')
    await waitFor(() => expect(calledUrls(fetchMock).filter((url) => url === '/api/v1/me').length).toBeGreaterThan(1))
    await new Promise((resolve) => setTimeout(resolve, 0))
    expect(screen.queryByRole('heading', { name: '이용약관' })).toBeNull()
  })
})
