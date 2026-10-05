import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { AuthProvider } from '../auth/AuthContext'
import { NoticeProvider } from '../auth/NoticeContext'
import { RegionProvider } from '../region/RegionContext'
import { MyLibrariesPage } from './MyLibrariesPage'
import { MyLibraryProvider } from './MyLibraryContext'

/** S10 `/my-libraries` (cycle 06 step 12) against a fake server. */

const TREE = [
  { id: 11, name: '서울특별시', shortName: '서울', children: [{ id: 1102, name: '중구' }, { id: 1114, name: '마포구' }] },
]

function lib(id: number, name: string, eligibility: string, matchedBy: string[] = []) {
  return { id, name, eligibility, matchedBy, visitRequired: false, cautioned: false, eligibilitySummary: null }
}

const LIBRARIES = [
  lib(2, '고흥군립도서관 전자도서관', 'OPEN'),
  lib(12, '서울도서관 전자도서관', 'ELIGIBLE', ['RESIDENCE', 'WORK']),
  lib(15, '마포구립 전자도서관', 'ELIGIBLE', ['RESIDENCE']),
  lib(20, '부산시립 전자도서관', 'UNKNOWN'),
  lib(30, '<b>꺾쇠</b> 도서관', 'UNKNOWN'),
]

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

interface Fake {
  signedIn: boolean
  libraryIds: number[]
  /** Status for the next PUT/DELETE of a library. */
  changeStatus: number
  changeCode: string
  calls: string[]
}

let fake: Fake

beforeEach(() => {
  document.cookie = 'XSRF-TOKEN=csrf; path=/'
  fake = { signedIn: true, libraryIds: [12], changeStatus: 204, changeCode: '', calls: [] }
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string, init?: RequestInit) => {
      const method = (init?.method ?? 'GET').toUpperCase()
      if (url === '/api/v1/me') return fake.signedIn ? json(200, { id: 7, loginMethods: ['KAKAO'], consents: [] }) : json(401, {})
      if (url === '/api/v1/auth/refresh') return json(401, {})
      if (url === '/api/v1/regions') return json(200, TREE)
      if (url.startsWith('/api/v1/regions/resolutions?ids=')) {
        const ids = url.split('=')[1].split(',').map(Number)
        return json(200, ids.map((id) => ({ id, status: 'ACTIVE', label: null, replacements: [] })))
      }
      if (url === '/api/v1/me/regions') return json(200, { residenceRegionIds: [1114], workRegionIds: [1102] })
      if (url === '/api/v1/me/libraries') return json(200, { libraryIds: fake.libraryIds, max: 20 })
      if (url.startsWith('/api/v1/libraries')) {
        fake.calls.push(`GET ${url}`)
        return json(200, { libraries: LIBRARIES })
      }
      if (url.startsWith('/api/v1/me/libraries/')) {
        fake.calls.push(`${method} ${url}`)
        if (fake.changeStatus === 204) return new Response(null, { status: 204 })
        return json(fake.changeStatus, { title: 'x', status: fake.changeStatus, code: fake.changeCode })
      }
      throw new Error(`unexpected ${method} ${url}`)
    }),
  )
})

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
  localStorage.clear()
})

function renderPage() {
  return render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <AuthProvider>
        <NoticeProvider>
          <MyLibraryProvider>
            <RegionProvider>
              <MemoryRouter initialEntries={['/my-libraries']}>
                <Routes>
                  <Route path="/my-libraries" element={<MyLibrariesPage />} />
                  <Route path="/" element={<p>S1</p>} />
                </Routes>
              </MemoryRouter>
            </RegionProvider>
          </MyLibraryProvider>
        </NoticeProvider>
      </AuthProvider>
    </QueryClientProvider>,
  )
}

const section = (name: string) => screen.getByRole('region', { name })

async function ready() {
  renderPage()
  await screen.findByRole('region', { name: '직접 추가한 도서관' })
}

describe('S10 sections', () => {
  it('asks the public list with the resolved regions and sorts it into the six sections', async () => {
    await ready()

    expect(fake.calls[0]).toBe('GET /api/v1/libraries?residenceRegionIds=1114&workRegionIds=1102')
    // the long line on wider screens, the short one on phones (user request 2026-10-05)
    expect(screen.getByText('누구나 가입하는 도서관 1곳도 늘 내 도서관에 들어 있어요')).toBeTruthy()
    expect(screen.getByText('누구나 가입 1곳은 늘 포함돼요')).toBeTruthy()
    // 지역 선택: both fields with one [변경], each followed by its automatic list (user requests 2026-10-05)
    const chosenRegions = section('지역 선택')
    expect(chosenRegions.textContent).toContain('거주서울 마포구')
    expect(chosenRegions.textContent).toContain('직장서울 중구')
    expect(within(chosenRegions).getAllByRole('button', { name: '지역 선택 변경' })).toHaveLength(1)
    expect(screen.queryByRole('region', { name: '지역으로 자동' })).toBeNull()
    // a library both regions match is in both lists; "거주 지역으로 자동 2곳" is one line, folded
    const byResidence = within(chosenRegions).getByRole('group', { name: '거주 지역으로 자동' })
    const byWork = within(chosenRegions).getByRole('group', { name: '직장 지역으로 자동' })
    const residenceToggle = within(byResidence).getByRole('button', { name: '거주 지역으로 자동 2곳' })
    const workToggle = within(byWork).getByRole('button', { name: '직장 지역으로 자동 1곳' })
    expect(residenceToggle.getAttribute('aria-expanded')).toBe('false')
    expect(within(byResidence).queryByText('서울도서관 전자도서관')).toBeNull()
    const user = userEvent.setup()
    await user.click(residenceToggle)
    await user.click(workToggle)
    expect(residenceToggle.getAttribute('aria-expanded')).toBe('true')
    expect(within(byResidence).getByText('서울도서관 전자도서관')).toBeTruthy()
    expect(within(byWork).getByText('서울도서관 전자도서관')).toBeTruthy()
    // the order inside the section: 거주, its list, 직장, its list
    const text = chosenRegions.textContent!
    expect(text.indexOf('거주서울')).toBeLessThan(text.indexOf('거주 지역으로 자동'))
    expect(text.indexOf('거주 지역으로 자동')).toBeLessThan(text.indexOf('직장서울'))
    expect(text.indexOf('직장서울')).toBeLessThan(text.indexOf('직장 지역으로 자동'))
    expect(within(section('직접 추가한 도서관')).getByText('1/20')).toBeTruthy()
  })

  it('a library no longer in the list says so, can be removed and counts', async () => {
    fake.libraryIds = [12, 999]
    await ready()

    const added = section('직접 추가한 도서관')
    expect(within(added).getByText('2/20')).toBeTruthy()
    expect(within(added).getByText('지금은 쓸 수 없는 도서관이에요')).toBeTruthy()
    expect(within(added).getByRole('button', { name: '쓸 수 없는 도서관 빼기' })).toBeTruthy()
  })

  it('draws library names as text only', async () => {
    await ready()
    await userEvent.setup().type(screen.getByRole('searchbox', { name: '도서관 이름으로 찾기' }), '꺾쇠')

    expect(screen.getByText('<b>꺾쇠</b> 도서관')).toBeTruthy()
    expect(document.querySelector('b')).toBeNull()
  })
})

describe('S10 name search', () => {
  it('ignores spaces and Unicode form, and finds part of a name', async () => {
    await ready()
    const user = userEvent.setup()
    const box = screen.getByRole('searchbox', { name: '도서관 이름으로 찾기' })

    await user.type(box, '서울 도서관')
    expect(screen.getByRole('button', { name: '서울도서관 전자도서관 이미 있음' })).toBeTruthy()

    await user.clear(box)
    await user.type(box, '마포'.normalize('NFD'))
    expect(screen.getByRole('button', { name: '마포구립 전자도서관 추가' })).toBeTruthy()

    await user.clear(box)
    await user.type(box, '없는이름')
    expect(screen.getByText('이름이 맞는 도서관이 없어요')).toBeTruthy()
  })

  it('libraries from the regions and open to anyone can be added; only added ones are 이미 있음', async () => {
    await ready()
    const user = userEvent.setup()
    const box = screen.getByRole('searchbox', { name: '도서관 이름으로 찾기' })

    await user.type(box, '전자도서관')

    expect((screen.getByRole('button', { name: '서울도서관 전자도서관 이미 있음' }) as HTMLButtonElement).disabled).toBe(true)
    expect((screen.getByRole('button', { name: '마포구립 전자도서관 추가' }) as HTMLButtonElement).disabled).toBe(false)
    expect((screen.getByRole('button', { name: '고흥군립도서관 전자도서관 추가' }) as HTMLButtonElement).disabled).toBe(false)
  })
})

describe('S10 saves each press at once', () => {
  async function addMapo() {
    await ready()
    const user = userEvent.setup()
    await user.type(screen.getByRole('searchbox', { name: '도서관 이름으로 찾기' }), '마포')
    await user.click(screen.getByRole('button', { name: '마포구립 전자도서관 추가' }))
    return user
  }

  it('추가 and 빼기', async () => {
    const user = await addMapo()

    await waitFor(() => expect(fake.calls).toContain('PUT /api/v1/me/libraries/15'))
    expect(within(section('직접 추가한 도서관')).getByText('2/20')).toBeTruthy()

    await user.click(screen.getByRole('button', { name: '마포구립 전자도서관 빼기' }))
    await waitFor(() => expect(fake.calls).toContain('DELETE /api/v1/me/libraries/15'))
    expect(within(section('직접 추가한 도서관')).getByText('1/20')).toBeTruthy()
  })

  it.each([
    [409, 'library_limit_reached', '직접 추가는 20곳까지예요. 하나를 빼면 추가할 수 있어요'],
    [404, 'library_not_found', '지금은 추가할 수 없는 도서관이에요. 목록을 다시 확인해 주세요'],
    [500, 'internal_error', '저장하지 못했어요. 잠시 뒤 다시 해 주세요'],
  ])('%i goes back and says so', async (status, code, message) => {
    fake.changeStatus = status
    fake.changeCode = code
    await addMapo()

    expect((await screen.findByRole('alert')).textContent).toBe(message)
    expect(within(section('직접 추가한 도서관')).getByText('1/20')).toBeTruthy()
    expect(within(section('직접 추가한 도서관')).queryByText('마포구립 전자도서관')).toBeNull()
  })

  it('a failed 빼기 puts the row back', async () => {
    fake.changeStatus = 500
    await ready()

    await userEvent.setup().click(screen.getByRole('button', { name: '서울도서관 전자도서관 빼기' }))

    expect((await screen.findByRole('alert')).textContent).toBe('저장하지 못했어요. 잠시 뒤 다시 해 주세요')
    expect(within(section('직접 추가한 도서관')).getByText('서울도서관 전자도서관')).toBeTruthy()
  })

  it('at 20 the 추가 buttons are greyed out with the message (AC-8)', async () => {
    fake.libraryIds = Array.from({ length: 20 }, (_, i) => 1000 + i)
    await ready()

    await userEvent.setup().type(screen.getByRole('searchbox', { name: '도서관 이름으로 찾기' }), '마포')

    expect((screen.getByRole('button', { name: '마포구립 전자도서관 추가' }) as HTMLButtonElement).disabled).toBe(true)
    expect(screen.getByText('직접 추가는 20곳까지예요. 하나를 빼면 추가할 수 있어요')).toBeTruthy()
  })
})

describe('S10 address', () => {
  it('before login goes to S1', async () => {
    fake.signedIn = false
    renderPage()

    expect(await screen.findByText('S1')).toBeTruthy()
  })

  it('says 불러오는 중 while the login is checked', () => {
    renderPage()

    expect(screen.getByText('불러오는 중')).toBeTruthy()
  })
})
