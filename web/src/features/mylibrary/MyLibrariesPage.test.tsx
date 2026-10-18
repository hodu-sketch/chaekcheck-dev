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
  /** Rows of `GET /api/v1/libraries`. */
  libraries: object[]
  /** The account's regions; `PUT /api/v1/me/regions` answers `regionsStatus` (cycle 22 D6). */
  regions: { residenceRegionIds: number[]; workRegionIds: number[] }
  regionsStatus: number
}

let fake: Fake

beforeEach(() => {
  document.cookie = 'XSRF-TOKEN=csrf; path=/'
  fake = {
    signedIn: true,
    libraryIds: [12],
    changeStatus: 204,
    changeCode: '',
    calls: [],
    libraries: LIBRARIES,
    regions: { residenceRegionIds: [1114], workRegionIds: [1102] },
    regionsStatus: 200,
  }
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
      if (url === '/api/v1/me/regions' && method === 'PUT') {
        fake.calls.push(`PUT ${url} ${init!.body as string}`)
        if (fake.regionsStatus !== 200) return json(fake.regionsStatus, { title: 'x', status: fake.regionsStatus, code: 'x' })
        fake.regions = JSON.parse(init!.body as string)
        return json(200, fake.regions)
      }
      if (url === '/api/v1/me/regions') return json(200, fake.regions)
      if (url === '/api/v1/me/libraries') return json(200, { libraryIds: fake.libraryIds, max: 20 })
      if (url.startsWith('/api/v1/libraries')) {
        fake.calls.push(`GET ${url}`)
        return json(200, { libraries: fake.libraries })
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

/** The region picker (user 2026-10-19): [+] shows a 시도 and a 시군구 select; picking a 시군구 adds it at once. */
async function openPicker(user: ReturnType<typeof userEvent.setup>, row: HTMLElement, field: string) {
  const add = (await within(row).findByRole('button', { name: `${field} 추가` })) as HTMLButtonElement
  await waitFor(() => expect(add.disabled).toBe(false))
  await user.click(add)
}

async function pickRegion(user: ReturnType<typeof userEvent.setup>, row: HTMLElement, field: string, sigungu: string) {
  await openPicker(user, row, field)
  await user.selectOptions(within(row).getByRole('combobox', { name: `${field} 시도` }), '서울')
  await user.selectOptions(within(row).getByRole('combobox', { name: `${field} 시군구` }), sigungu)
}

const sigunguOption = (row: HTMLElement, name: RegExp) =>
  within(row).getByRole('option', { name }) as HTMLOptionElement

async function ready() {
  renderPage()
  await screen.findByRole('region', { name: '직접 추가한 도서관' })
}

describe('S10 sections', () => {
  it('asks the public list with the resolved regions and sorts it into the six sections', async () => {
    await ready()

    expect(fake.calls[0]).toBe('GET /api/v1/libraries?residenceRegionIds=1114&workRegionIds=1102')
    // the long line on wider screens, the short one on phones (user request 2026-10-05)
    // cycle 22 R11-2 wording
    expect(screen.getByText('어디 살든 가입되는 도서관 1곳도 늘 내 도서관에 들어 있어요')).toBeTruthy()
    expect(screen.getByText('어디 살든 가입 1곳은 늘 포함돼요')).toBeTruthy()
    // 지역 선택: a 사는 곳 and an 일하는 곳 row, each its chips and grid, then its automatic list (cycle 22 R11-1)
    const chosenRegions = section('지역 선택')
    const residenceRow = within(chosenRegions).getByRole('group', { name: '사는 곳' })
    const workRow = within(chosenRegions).getByRole('group', { name: '일하는 곳' })
    await waitFor(() =>
      expect(within(residenceRow).getByRole('list', { name: '고른 사는 곳' }).textContent).toBe('서울 마포구'),
    )
    expect(within(workRow).getByRole('list', { name: '고른 일하는 곳' }).textContent).toBe('서울 중구')
    expect(screen.queryByRole('region', { name: '지역으로 자동' })).toBeNull()
    // a library both regions match is in both lists; "거주 2곳" is one line, folded
    const byResidence = within(chosenRegions).getByRole('group', { name: '거주' })
    const byWork = within(chosenRegions).getByRole('group', { name: '직장' })
    const residenceToggle = within(byResidence).getByRole('button', { name: '거주 2곳' })
    const workToggle = within(byWork).getByRole('button', { name: '직장 1곳' })
    expect(residenceToggle.getAttribute('aria-expanded')).toBe('false')
    expect(within(byResidence).queryByText('서울도서관 전자도서관')).toBeNull()
    const user = userEvent.setup()
    await user.click(residenceToggle)
    await user.click(workToggle)
    expect(residenceToggle.getAttribute('aria-expanded')).toBe('true')
    expect(within(byResidence).getByText('서울도서관 전자도서관')).toBeTruthy()
    expect(within(byWork).getByText('서울도서관 전자도서관')).toBeTruthy()
    // the order inside the section: 사는 곳, its list, 일하는 곳, its list
    const text = chosenRegions.textContent!
    expect(text.indexOf('사는 곳')).toBeLessThan(text.indexOf('거주 '))
    expect(text.indexOf('거주 ')).toBeLessThan(text.indexOf('일하는 곳'))
    expect(text.indexOf('일하는 곳')).toBeLessThan(text.indexOf('직장 '))
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
    expect(screen.getByRole('button', { name: '서울도서관 전자도서관 직접 추가함' })).toBeTruthy()

    await user.clear(box)
    await user.type(box, '마포'.normalize('NFD'))
    expect(screen.getByRole('button', { name: '마포구립 전자도서관 추가' })).toBeTruthy()

    await user.clear(box)
    await user.type(box, '없는이름')
    expect(screen.getByText('찾는 도서관이 없어요')).toBeTruthy()
  })

  it('starts from two characters, names starting with them first, then in 가나다 order (cycle 07)', async () => {
    await ready()
    const user = userEvent.setup()
    const box = screen.getByRole('searchbox', { name: '도서관 이름으로 찾기' })

    await user.type(box, '서')
    expect(within(section('직접 도서관 추가')).queryByRole('button', { name: /추가$/ })).toBeNull()
    expect(screen.queryByText('찾는 도서관이 없어요')).toBeNull()

    await user.clear(box)
    await user.type(box, '전자')
    const names = within(section('직접 도서관 추가'))
      .getAllByRole('listitem')
      .map((item) => item.textContent!.replace(/(추가|직접 추가함)$/, ''))
    expect(names).toEqual(['고흥군립도서관 전자도서관', '마포구립 전자도서관', '부산시립 전자도서관', '서울도서관 전자도서관'])
  })

  it('libraries from the regions and open to anyone can be added; only added ones are 직접 추가함', async () => {
    await ready()
    const user = userEvent.setup()
    const box = screen.getByRole('searchbox', { name: '도서관 이름으로 찾기' })

    await user.type(box, '전자도서관')

    expect((screen.getByRole('button', { name: '서울도서관 전자도서관 직접 추가함' }) as HTMLButtonElement).disabled).toBe(true)
    expect((screen.getByRole('button', { name: '마포구립 전자도서관 추가' }) as HTMLButtonElement).disabled).toBe(false)
    expect((screen.getByRole('button', { name: '고흥군립도서관 전자도서관 추가' }) as HTMLButtonElement).disabled).toBe(false)
  })
})

/** Cycle 09 (handoff step 5-4): "이름 · 업체 · 대출 방식" in the three lists. */
describe('S10 library classification', () => {
  const DONGDAEMUN = [
    { ...lib(40, '동대문구립도서관', 'ELIGIBLE', ['RESIDENCE']), vendor: 'YES24', lendingModel: 'OWNED', orderNo: 1 },
    { ...lib(41, '동대문구립도서관', 'ELIGIBLE', ['RESIDENCE']), vendor: 'KYOBO', lendingModel: 'SUBSCRIPTION', orderNo: 1 },
    { ...lib(42, '동대문구립도서관', 'UNKNOWN'), vendor: 'YES24', lendingModel: 'OWNED', orderNo: 2 },
    { ...lib(43, '경기도사이버도서관', 'OPEN'), vendor: 'PORTAL', lendingModel: null, orderNo: 1 },
  ]

  beforeEach(() => {
    fake.libraries = [...LIBRARIES, ...DONGDAEMUN]
    fake.libraryIds = [40, 42, 999]
  })

  it('shows it in the region list, the name search and the added libraries', async () => {
    await ready()
    const user = userEvent.setup()

    const byResidence = within(section('지역 선택')).getByRole('group', { name: '거주' })
    await user.click(within(byResidence).getByRole('button', { name: /^거주 \d+곳/ }))
    const regionNames = within(byResidence).getAllByRole('listitem').map((item) => item.textContent)
    expect(regionNames).toContain('동대문구립도서관 · YES24 · 소장')
    expect(regionNames).toContain('동대문구립도서관 · 교보 · 구독')
    // a library without the values keeps its name only
    expect(regionNames).toContain('마포구립 전자도서관')

    await user.click(screen.getByText(/어디 살든 가입되는 도서관/))
    expect(screen.getByText('경기도사이버도서관').parentElement!.textContent).toBe('경기도사이버도서관 · 통합 포털')

    await user.type(screen.getByRole('searchbox', { name: '도서관 이름으로 찾기' }), '동대문')
    const found = within(section('직접 도서관 추가'))
      .getAllByRole('listitem')
      .map((item) => item.textContent!.replace(/(추가|직접 추가함)$/, ''))
    expect(found).toEqual(['동대문구립도서관 · 교보 · 구독', '동대문구립도서관 · YES24 · 소장', '동대문구립도서관 · YES24 · 소장 · 2번째 사이트'])

    const added = within(section('직접 추가한 도서관')).getAllByRole('listitem').map((item) => item.textContent!.replace(/빼기$/, ''))
    expect(added).toEqual(['동대문구립도서관 · YES24 · 소장', '동대문구립도서관 · YES24 · 소장 · 2번째 사이트', '지금은 쓸 수 없는 도서관이에요'])
  })

  it('a library no longer in the list has no classification', async () => {
    await ready()

    const gone = within(section('직접 추가한 도서관')).getByText('지금은 쓸 수 없는 도서관이에요')
    expect(gone.closest('li')!.textContent).toBe('지금은 쓸 수 없는 도서관이에요빼기')
    expect(screen.getByRole('button', { name: '쓸 수 없는 도서관 빼기' })).toBeTruthy()
  })

  it('tells rows of the same name apart in the button labels (AC-9)', async () => {
    await ready()
    await userEvent.setup().type(screen.getByRole('searchbox', { name: '도서관 이름으로 찾기' }), '동대문')

    expect(screen.getByRole('button', { name: '동대문구립도서관 YES24 소장 직접 추가함' })).toBeTruthy()
    expect(screen.getByRole('button', { name: '동대문구립도서관 YES24 소장 2번째 사이트 직접 추가함' })).toBeTruthy()
    expect(screen.getByRole('button', { name: '동대문구립도서관 교보 구독 추가' })).toBeTruthy()
    expect(screen.getByRole('button', { name: '동대문구립도서관 YES24 소장 빼기' })).toBeTruthy()
    expect(screen.getByRole('button', { name: '동대문구립도서관 YES24 소장 2번째 사이트 빼기' })).toBeTruthy()
  })

  it('finds a row by its vendor too (AC-6)', async () => {
    await ready()
    await userEvent.setup().type(screen.getByRole('searchbox', { name: '도서관 이름으로 찾기' }), '동대문 교보')

    expect(within(section('직접 도서관 추가')).getAllByRole('button').map((button) => button.getAttribute('aria-label'))).toEqual([
      '동대문구립도서관 교보 구독 추가',
    ])
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

/** Cycle 07 (ADR-0057, AC-18): before login the same screen, saved in this browser only. */
describe('S10 before login', () => {
  beforeEach(() => {
    fake.signedIn = false
  })

  it('opens and keeps the added libraries in this browser, never on the server', async () => {
    await ready()
    const user = userEvent.setup()
    await user.type(screen.getByRole('searchbox', { name: '도서관 이름으로 찾기' }), '마포')
    await user.click(screen.getByRole('button', { name: '마포구립 전자도서관 추가' }))

    expect(within(section('직접 추가한 도서관')).getByText('1/20')).toBeTruthy()
    expect(JSON.parse(localStorage.getItem('chaekcheck.chosenLibraries.v1')!)).toEqual([15])
    await user.click(screen.getByRole('button', { name: '마포구립 전자도서관 빼기' }))
    expect(JSON.parse(localStorage.getItem('chaekcheck.chosenLibraries.v1')!)).toEqual([])
    expect(fake.calls.filter((call) => call.includes('/api/v1/me/'))).toEqual([])
  })

  it('stops at 20', async () => {
    localStorage.setItem('chaekcheck.chosenLibraries.v1', JSON.stringify([...Array(20).keys()].map((n) => 100 + n)))
    await ready()
    await userEvent.setup().type(screen.getByRole('searchbox', { name: '도서관 이름으로 찾기' }), '마포')

    expect((screen.getByRole('button', { name: '마포구립 전자도서관 추가' }) as HTMLButtonElement).disabled).toBe(true)
    expect(screen.getAllByText('직접 추가는 20곳까지예요. 하나를 빼면 추가할 수 있어요').length).toBeGreaterThan(0)
  })

  it('says so when the browser will not store it', async () => {
    await ready()
    const setItem = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new DOMException('blocked', 'QuotaExceededError')
    })
    const user = userEvent.setup()
    await user.type(screen.getByRole('searchbox', { name: '도서관 이름으로 찾기' }), '마포')
    await user.click(screen.getByRole('button', { name: '마포구립 전자도서관 추가' }))

    expect((await screen.findByRole('alert')).textContent).toBe('이 브라우저에 저장하지 못했어요')
    expect(within(section('직접 추가한 도서관')).getByText('0/20')).toBeTruthy()
    setItem.mockRestore()
  })
})

describe('S10 address', () => {
  it('says 불러오는 중 while the login is checked', () => {
    renderPage()

    expect(screen.getByText('불러오는 중')).toBeTruthy()
  })
})

/** User request 2026-10-14: at most 10 libraries at a time in each long list, then "더 보기" for 10 more. */
describe('S10 long lists', () => {
  const names = (scope: HTMLElement) => within(scope).queryAllByRole('listitem').length

  it('어디 살든 가입, a region and the name search show 10, then 10 more per "더 보기"', async () => {
    fake.libraries = [
      ...Array.from({ length: 25 }, (_, i) => lib(100 + i, `누구나 도서관 ${i + 1}`, 'OPEN')),
      ...Array.from({ length: 12 }, (_, i) => lib(200 + i, `마포 도서관 ${i + 1}`, 'ELIGIBLE', ['RESIDENCE'])),
    ]
    await ready()
    const user = userEvent.setup()

    const openToAll = screen.getByText('어디 살든 가입되는 도서관 25곳도 늘 내 도서관에 들어 있어요').closest('details')!
    await user.click(within(openToAll).getByText('어디 살든 가입되는 도서관 25곳도 늘 내 도서관에 들어 있어요'))
    expect(names(openToAll)).toBe(10)
    await user.click(within(openToAll).getByRole('button', { name: '더 보기' }))
    expect(names(openToAll)).toBe(20)
    await user.click(within(openToAll).getByRole('button', { name: '더 보기' }))
    expect(names(openToAll)).toBe(25)
    expect(within(openToAll).queryByRole('button', { name: '더 보기' })).toBeNull()

    const byResidence = within(section('지역 선택')).getByRole('group', { name: '거주' })
    await user.click(within(byResidence).getByRole('button', { name: '거주 12곳' }))
    expect(names(byResidence)).toBe(10)
    await user.click(within(byResidence).getByRole('button', { name: '더 보기' }))
    expect(names(byResidence)).toBe(12)

    const add = section('직접 도서관 추가')
    await user.type(within(add).getByRole('searchbox', { name: '도서관 이름으로 찾기' }), '도서관')
    expect(within(add).getAllByRole('button', { name: /추가/ })).toHaveLength(10)
    await user.click(within(add).getByRole('button', { name: '더 보기' }))
    expect(within(add).getAllByRole('button', { name: /추가/ })).toHaveLength(20)
  })
})


/** Cycle 22 step 2 (R11, AC-13, D6): the grid in place of [변경] and the dialog, every check saved at once. */
describe('S10 region grid (cycle 22)', () => {
  const putCalls = () => fake.calls.filter((call) => call.startsWith('PUT /api/v1/me/regions'))

  it('has the new first sentence, no [변경] and no dialog, a picker per row', async () => {
    await ready()
    const user = userEvent.setup()

    expect(screen.getByText('검색하면 여기 있는 도서관에서 먼저 찾아요')).toBeTruthy()
    expect(screen.queryByText(/스위치/)).toBeNull()
    expect(screen.queryByRole('button', { name: /변경/ })).toBeNull()
    expect(screen.queryByRole('dialog')).toBeNull()
    const residenceRow = within(section('지역 선택')).getByRole('group', { name: '사는 곳' })
    await openPicker(user, residenceRow, '사는 곳')
    expect(within(residenceRow).getByRole('combobox', { name: '사는 곳 시도' })).toBeTruthy()
    const mapo = sigunguOption(residenceRow, /마포구/)
    expect(mapo.textContent).toBe('✓ 마포구')
    expect(mapo.disabled).toBe(true)
    expect(document.body.textContent).not.toContain('누구나 가입')
  })

  it('a check saves to the account at once and asks the list again with the new regions', async () => {
    await ready()
    const user = userEvent.setup()
    const residenceRow = within(section('지역 선택')).getByRole('group', { name: '사는 곳' })

    await pickRegion(user, residenceRow, '사는 곳', '중구')

    await waitFor(() => expect(putCalls()).toEqual(['PUT /api/v1/me/regions {"residenceRegionIds":[1114,1102],"workRegionIds":[1102]}']))
    await waitFor(() =>
      expect(fake.calls).toContain('GET /api/v1/libraries?residenceRegionIds=1114%2C1102&workRegionIds=1102'),
    )
    expect(within(residenceRow).getByRole('list', { name: '고른 사는 곳' }).textContent).toBe('서울 마포구서울 중구')
    // the picker stays while the list is asked again; no hint after login
    expect(within(residenceRow).getByRole('button', { name: '사는 곳 추가' })).toBeTruthy()
    expect(screen.queryByText(/이 브라우저에만 저장됐어요/)).toBeNull()

    // a chip's × removes it the same way
    await user.click(within(residenceRow).getByRole('button', { name: '서울 마포구 빼기' }))
    await waitFor(() => expect(putCalls()).toHaveLength(2))
    expect(putCalls()[1]).toBe('PUT /api/v1/me/regions {"residenceRegionIds":[1102],"workRegionIds":[1102]}')
  })

  it('a failed save goes back and says so', async () => {
    fake.regionsStatus = 500
    await ready()
    const residenceRow = within(section('지역 선택')).getByRole('group', { name: '사는 곳' })
    const user = userEvent.setup()

    await pickRegion(user, residenceRow, '사는 곳', '중구')

    expect((await screen.findByRole('alert')).textContent).toBe('저장하지 못했어요. 잠시 뒤 다시 해 주세요')
    expect(within(residenceRow).getByRole('list', { name: '고른 사는 곳' }).textContent).toBe('서울 마포구')
    await openPicker(user, residenceRow, '사는 곳')
    const jung = sigunguOption(residenceRow, /중구/)
    expect(jung.textContent).toBe('중구')
    expect(jung.disabled).toBe(false)
  })

  it('the 일하는 곳 grid is folded behind [일하는 곳도 더하기] while it has no region', async () => {
    fake.signedIn = false
    await ready()
    const user = userEvent.setup()
    const regionSection = section('지역 선택')

    expect(within(regionSection).getByRole('group', { name: '사는 곳' })).toBeTruthy()
    expect(within(regionSection).queryByRole('group', { name: '일하는 곳' })).toBeNull()
    expect(within(regionSection).queryByText('직장 지역으로')).toBeNull()

    await user.click(within(regionSection).getByRole('button', { name: '일하는 곳도 더하기' }))

    const workRow = within(regionSection).getByRole('group', { name: '일하는 곳' })
    expect(await within(workRow).findByRole('button', { name: '일하는 곳 추가' })).toBeTruthy()
    expect(within(regionSection).queryByRole('button', { name: '일하는 곳도 더하기' })).toBeNull()
  })

  it('before login a check is kept in this browser, and the save hint shows under the rows until ×', async () => {
    fake.signedIn = false
    await ready()
    const user = userEvent.setup()
    const regionSection = section('지역 선택')
    expect(within(regionSection).queryByText(/이 브라우저에만 저장됐어요/)).toBeNull()

    await pickRegion(user, within(regionSection).getByRole('group', { name: '사는 곳' }), '사는 곳', '마포구')

    expect(JSON.parse(localStorage.getItem('chaekcheck.regions.v1')!)).toEqual({ residence: [1114], work: [] })
    expect(putCalls()).toEqual([])
    expect(within(regionSection).getByText('이 브라우저에만 저장됐어요. 로그인하면 다른 기기에서도 그대로예요')).toBeTruthy()
    await user.click(within(regionSection).getByRole('button', { name: '저장 안내 닫기' }))
    expect(within(regionSection).queryByText(/이 브라우저에만 저장됐어요/)).toBeNull()
  })
})
