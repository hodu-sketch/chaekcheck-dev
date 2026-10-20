import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { MemoryRouter } from 'react-router'
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import type { VisitFilter } from '../../api/types'
import { AuthProvider } from '../auth/AuthContext'
import { NoticeProvider } from '../auth/NoticeContext'
import { MyLibraryProvider } from '../mylibrary/MyLibraryContext'
import { REGION_IMPORT_KEY } from '../mylibrary/regionImport'
import { RegionProvider } from '../region/RegionContext'
import { REGION_STORAGE_KEY } from '../region/regionStorage'
import {
  LibraryScopeChip,
  LibraryScopeLine,
  LibraryScopeSheet,
  SCOPE_TEXT,
  scopeLineText,
  type BoxFocus,
  type ScopeSearched,
} from './LibraryScopeLine'
import { planCountKey, type PlanCount } from './searchSettings'

/** The condition line's words: the [조건 수정] button's description (first screen redesign, user 2026-10-19). */
const conditionOf = (button: HTMLElement) => document.getElementById(button.getAttribute('aria-describedby') ?? '')?.textContent
/**
 * Cycle 22 step 3 (R1, R3, R4-2, R9, D4, D5, D7): the "찾을 도서관" line on wider screens, its box and what they say,
 * with a fake server behind the region and 내 도서관 providers.
 */

const TREE = [
  { id: 11, name: '서울특별시', shortName: '서울', children: [{ id: 1102, name: '중구' }, { id: 1114, name: '마포구' }] },
  { id: 41, name: '경기도', shortName: '경기', children: [{ id: 4113, name: '성남시' }] },
]

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

interface Fake {
  signedIn: boolean
  librariesStatus: number
  libraryIds: number[]
  libraryLists: number
}
let fake: Fake

beforeAll(() => {
  // Radix positions popovers with ResizeObserver, which jsdom does not have
  globalThis.ResizeObserver ??= class {
    observe() {}
    unobserve() {}
    disconnect() {}
  }
})

beforeEach(() => {
  fake = { signedIn: false, librariesStatus: 200, libraryIds: [], libraryLists: 0 }
  localStorage.setItem(REGION_IMPORT_KEY, '[7]')
  // the login check needs it to answer signed-out (not "login off")
  document.cookie = 'XSRF-TOKEN=csrf; path=/'
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string) => {
      if (url === '/api/v1/me') return fake.signedIn ? json(200, { id: 7, loginMethods: ['KAKAO'], consents: [] }) : json(401, {})
      if (url === '/api/v1/auth/refresh') return json(401, {})
      if (url === '/api/v1/auth/providers') return json(200, ['KAKAO'])
      if (url === '/api/v1/regions') return json(200, TREE)
      if (url.startsWith('/api/v1/regions/resolutions?ids=')) {
        const ids = url.split('=')[1].split(',').map(Number)
        return json(200, ids.map((id) => ({ id, status: 'ACTIVE', label: null, replacements: [] })))
      }
      if (url === '/api/v1/me/regions') return json(200, { residenceRegionIds: [], workRegionIds: [] })
      if (url === '/api/v1/me/libraries') {
        return fake.librariesStatus === 200
          ? json(200, { libraryIds: fake.libraryIds, max: 20 })
          : json(fake.librariesStatus, { code: 'internal_error' })
      }
      if (url === '/api/v1/libraries') {
        fake.libraryLists += 1
        return json(200, {
          libraries: [
            { id: 12, name: '마포구립 전자도서관', eligibility: 'ELIGIBLE', matchedBy: [] },
            { id: 207, name: '울산동부도서관', eligibility: 'OPEN', matchedBy: [] },
            { id: 208, name: '김천시립도서관', eligibility: 'OPEN', matchedBy: [] },
          ],
        })
      }
      throw new Error(`unexpected ${url}`)
    }),
  )
})

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
  localStorage.clear()
})

interface Options {
  visit?: VisitFilter
  planCount?: PlanCount | null
  openCount?: number | null
  searched?: ScopeSearched | null
}

function renderLine(options: Options = {}) {
  const onVisitChange = vi.fn()
  const onOpenCount = vi.fn()
  function Harness() {
    const [box, setBox] = useState<BoxFocus | null>(null)
    return (
      <>
        <LibraryScopeLine
          visit={options.visit ?? 'any'}
          onVisitChange={onVisitChange}
          planCount={options.planCount ?? null}
          openCount={options.openCount ?? null}
          onOpenCount={onOpenCount}
          searched={options.searched}
          box={box}
          onBoxChange={setBox}
        />
        <p>outside</p>
      </>
    )
  }
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <AuthProvider>
        <NoticeProvider>
          <MyLibraryProvider>
            <RegionProvider>
              <MemoryRouter>
                <Harness />
              </MemoryRouter>
            </RegionProvider>
          </MyLibraryProvider>
        </NoticeProvider>
      </AuthProvider>
    </QueryClientProvider>,
  )
  return { user: userEvent.setup(), onVisitChange, onOpenCount }
}

const guestRegions = (residence: number[], work: number[] = []) =>
  localStorage.setItem(REGION_STORAGE_KEY, JSON.stringify({ residence, work }))
const folded = () => screen.findByRole('button', { name: '지역을 선택해주세요' })
// the main page's box on wider screens: a part of the page, no window (user 2026-10-20)
const box = () => screen.findByRole('region', { name: '찾을 도서관' })
const sheetDialog = () => screen.findByRole('dialog', { name: '찾을 도서관' })

describe('a first visitor (R1-2)', () => {
  it('the condition card with [지역 선택] and no grey line under it (user 2026-10-20 words)', async () => {
    renderLine()
    const button = await screen.findByRole('button', { name: '지역을 선택해주세요' })
    const card = screen.getByTestId('scope-condition')
    expect(card.textContent).toBe('지역을 선택하시면 빌릴 수 있는 전자도서관이 늘어나요지역을 선택해주세요')
    expect(conditionOf(button)).toBe('지역을 선택하시면 빌릴 수 있는 전자도서관이 늘어나요')
    expect(screen.queryByText(/안 골라도/)).toBeNull()
    expect(screen.queryByText(/어디서 빌리실 건가요/)).toBeNull()
  })

  it('with regions: [조건 수정] and no grey line', async () => {
    guestRegions([1114])
    renderLine()
    const button = await folded()
    await waitFor(() => expect(conditionOf(button)).toBe('서울 마포구에 살아요'))
    expect(screen.getByTestId('scope-condition').textContent).toBe('서울 마포구에 살아요지역을 선택해주세요')
    expect(screen.queryByText(/안 골라도/)).toBeNull()
  })

  it('the button opens the box under the card, no window; no [일하는 곳도 더하기] beside it (user 2026-10-19, 2026-10-20)', async () => {
    const { user } = renderLine()
    expect(screen.queryByRole('button', { name: '일하는 곳도 더하기' })).toBeNull()
    const button = await screen.findByRole('button', { name: '지역을 선택해주세요' })
    expect(button.getAttribute('aria-expanded')).toBe('false')
    await user.click(button)
    const opened = await box()
    expect(button.getAttribute('aria-expanded')).toBe('true')
    expect(button.getAttribute('aria-controls')).toBe(opened.id)
    expect(within(opened).getByRole('group', { name: '거주' })).toBeTruthy()
    expect(screen.queryByRole('dialog')).toBeNull()
  })
})

describe('a results screen of a search made without regions (the line in the results before, user 2026-10-19)', () => {
  const searched = (overrides: Partial<ScopeSearched> = {}): ScopeSearched => ({
    planCount: 41,
    regionsPicked: false,
    picked: null,
    ...overrides,
  })

  it('says where it searched, with the number when known, and what a region adds', async () => {
    renderLine({ searched: searched() })
    const button = await screen.findByRole('button', { name: '지역을 선택해주세요' })
    expect(conditionOf(button)).toBe('어디 살든 가입되는 도서관 41곳에서 찾았어요')
    expect(screen.getByText('사는 곳을 알려 주시면 그 지역 도서관도 보여요')).toBeTruthy()
    cleanup()

    renderLine({ searched: searched({ planCount: null }) })
    expect(conditionOf(await screen.findByRole('button', { name: '지역을 선택해주세요' }))).toBe(
      '어디 살든 가입되는 도서관에서 찾았어요',
    )
  })

  it('a region saved since: the card shows it, and the grey line asks for [검색]', async () => {
    guestRegions([1114])
    renderLine({ searched: searched({ regionsPicked: true, picked: { field: 'residence', place: '서울 마포구' } }) })
    const button = await folded()
    await waitFor(() => expect(conditionOf(button)).toBe('서울 마포구에 살아요'))
    const hint = screen.getByRole('status')
    expect(hint.textContent).toBe('사는 곳을 서울 마포구로 골랐어요. 그 지역 도서관부터 보려면 [검색]을 눌러 주세요')
    expect(within(hint).getByText('[검색]').tagName).toBe('B')
    expect(screen.getByTestId('scope-condition').contains(hint)).toBe(false)
  })

  it('the words of the [검색] line', () => {
    expect(SCOPE_TEXT.pickedBefore({ field: 'residence', place: '서울 강서구' })).toBe(
      '사는 곳을 서울 강서구로 골랐어요. 그 지역 도서관부터 보려면 ',
    )
    expect(SCOPE_TEXT.pickedBefore({ field: 'work', place: '서울 강남구 외 2곳' })).toBe(
      '일하는 곳을 서울 강남구 외 2곳으로 골랐어요. 그 지역 도서관부터 보려면 ',
    )
    expect(SCOPE_TEXT.pickedBefore({ field: 'residence', place: '경기 성남시' })).toBe(
      '사는 곳을 경기 성남시로 골랐어요. 그 지역 도서관부터 보려면 ',
    )
    expect(SCOPE_TEXT.pickedBefore({ field: 'libraries' })).toBe('직접 추가한 도서관이 생겼어요. 그 도서관부터 보려면 ')
    expect(SCOPE_TEXT.pickedBefore(null)).toBe('지역을 바꿨어요. 바뀐 지역으로 찾으려면 ')
  })
})

describe('the folded line (R3)', () => {
  it('shows only the residence, short; work, 방문 가입 and the count are in the box (user 2026-10-19)', () => {
    expect(scopeLineText({ residence: '서울 강남구', work: null, chosen: 0 })).toBe('서울 강남구에 살아요')
    expect(scopeLineText({ residence: '서울 강남구', work: '경기 성남시', chosen: 2 })).toBe('서울 강남구에 살아요 · 경기 성남시에서 일해요')
    // without a residence the line names what there is
    expect(scopeLineText({ residence: null, work: '경기 성남시', chosen: 0 })).toBe('경기 성남시에서 일해요')
    expect(scopeLineText({ residence: null, work: null, chosen: 3 })).toBe('직접 추가한 도서관 3곳에서 찾아요')
    expect(scopeLineText({ residence: null, work: null, chosen: 0 })).toBeNull()
    // "외 N곳" when there are more (user 2026-10-20)
    expect(scopeLineText({ residence: '서울 강서구', work: '서울 중구', chosen: 0, moreResidence: 2, moreWork: 1 })).toBe(
      '서울 강서구 외 2곳에 살아요 · 서울 중구 외 1곳에서 일해요',
    )
  })

  it('names this browser\'s regions, several of one 시도 joined by ·', async () => {
    guestRegions([1114, 1102], [4113])
    renderLine({ visit: 'none' })
    const line = await folded()
    await waitFor(() => expect(conditionOf(line)).toBe('서울 마포구 외 1곳에 살아요 · 경기 성남시에서 일해요'))
  })

  it('work only: "내 도서관: 일하는 곳 …"', async () => {
    guestRegions([], [4113])
    renderLine()
    const line = await folded()
    await waitFor(() => expect(conditionOf(line)).toBe('경기 성남시에서 일해요'))
  })

  it('"N곳" only while the kept count was taken with the same inputs (D5)', async () => {
    guestRegions([1114])
    const inputs = { source: 'browser' as const, residence: [1114], work: [], libraryIds: [], visit: 'any' as const }
    renderLine({ planCount: { key: planCountKey(inputs), count: 68 } })
    const line = await folded()
    await waitFor(() => expect(conditionOf(line)).toBe('서울 마포구에 살아요'))
    cleanup()

    // another 방문 가입 빼기, another region, or the same regions taken after login: no number
    for (const other of [{ ...inputs, visit: 'none' as const }, { ...inputs, residence: [1102] }, { ...inputs, source: 'account' as const }]) {
      renderLine({ planCount: { key: planCountKey(other), count: 68 } })
      const plain = await folded()
      await waitFor(() => expect(conditionOf(plain)).toBe('서울 마포구에 살아요'))
      cleanup()
    }
  })
})

describe('the box (R1-4; one 거주 only since user 2026-10-20)', () => {
  it('holds only 거주 and the my page line: no 일하는 곳, 직접 추가, 가입 방법 or [마이페이지에서 전체 보기]', async () => {
    guestRegions([1114])
    const { user } = renderLine({ openCount: 39 })
    await user.click(await folded())
    const opened = await box()
    expect(within(opened).getAllByRole('heading').map((heading) => heading.textContent)).toEqual(['거주'])
    expect(within(opened).queryByRole('group', { name: '일하는 곳' })).toBeNull()
    expect(within(opened).queryByText(/직접 추가/)).toBeNull()
    expect(within(opened).queryByText(/어디 살든 가입되는 도서관/)).toBeNull()
    expect(within(opened).queryByRole('switch')).toBeNull()
    expect(within(opened).queryByText('가입 방법')).toBeNull()
    expect(within(opened).queryByRole('link', { name: '마이페이지에서 전체 보기' })).toBeNull()
    // "더 자세한 설정은 [마이페이지]에서 할 수 있어요", the word a bold underlined link (user 2026-10-20)
    expect(within(opened).getByText(/^더 자세한 설정은/).textContent).toBe('더 자세한 설정은 마이페이지에서 할 수 있어요')
    const link = within(opened).getByRole('link', { name: '마이페이지' })
    expect(link.getAttribute('href')).toBe('/my-libraries')
    expect(link.className).toContain('strongLink')
  })

  // one region: no + while one is chosen and no limit line; × first to pick another (user 2026-10-20)
  it('takes one 거주: no + and no limit line while one is chosen, + again after ×', async () => {
    guestRegions([1114])
    const { user } = renderLine()
    await user.click(await folded())
    const residence = within(await box()).getByRole('group', { name: '거주' })
    await waitFor(() => expect(within(residence).getByText('서울 마포구')).toBeTruthy())
    expect(within(residence).queryByRole('button', { name: '사는 곳 추가' })).toBeNull()
    expect(within(residence).queryByText(/^최대 \d+곳이에요$/)).toBeNull()

    await user.click(within(residence).getByRole('button', { name: '서울 마포구 빼기' }))
    expect(await within(residence).findByRole('button', { name: '사는 곳 추가' })).toBeTruthy()
  })

  // only the first 거주 is shown and changed; the rest stays as the my page set it (user 2026-10-20: "메인은 그냥 맨 앞에
  // 거주 조건만 바꾸는 걸로 해줘")
  it('shows and changes only the first 거주; further residences and the workplaces stay', async () => {
    guestRegions([1114, 1102], [4113])
    const { user } = renderLine()
    await user.click(await folded())
    const residence = within(await box()).getByRole('group', { name: '거주' })
    const chips = within(residence).getByRole('list', { name: '고른 사는 곳' })
    await waitFor(() => expect(within(chips).getAllByRole('listitem').map((item) => item.textContent)).toEqual([
      expect.stringContaining('서울 마포구'),
    ]))
    await user.click(within(residence).getByRole('button', { name: '서울 마포구 빼기' }))
    expect(JSON.parse(localStorage.getItem(REGION_STORAGE_KEY)!)).toMatchObject({ residence: [1102], work: [4113] })
    // the one left moves up into the box
    await waitFor(() => expect(within(residence).getByText('서울 중구')).toBeTruthy())
  })

  it('closes on ⓧ, on Esc and on the my page link', async () => {
    const { user } = renderLine()
    await user.click(await screen.findByRole('button', { name: '지역을 선택해주세요' }))
    await user.click(within(await box()).getByRole('button', { name: '닫기' }))
    await waitFor(() => expect(screen.queryByRole('region', { name: '찾을 도서관' })).toBeNull())

    await user.click(screen.getByRole('button', { name: '지역을 선택해주세요' }))
    await box()
    await user.keyboard('{Escape}')
    await waitFor(() => expect(screen.queryByRole('region', { name: '찾을 도서관' })).toBeNull())

    await user.click(screen.getByRole('button', { name: '지역을 선택해주세요' }))
    await user.click(within(await box()).getByRole('link', { name: '마이페이지' }))
    await waitFor(() => expect(screen.queryByRole('region', { name: '찾을 도서관' })).toBeNull())
  })

  it('the library list is not read, even with added libraries (D4)', async () => {
    localStorage.setItem('chaekcheck.chosenLibraries.v1', JSON.stringify([12]))
    guestRegions([1114])
    const { user } = renderLine()
    await user.click(await folded())
    await box()
    expect(fake.libraryLists).toBe(0)
  })

  it('before login a check is saved in this browser and the save hint shows under 거주 (R9)', async () => {
    const { user } = renderLine()
    await user.click(await screen.findByRole('button', { name: '지역을 선택해주세요' }))
    const opened = await box()
    const residence = within(opened).getByRole('group', { name: '거주' })
    const add = (await within(residence).findByRole('button', { name: '사는 곳 추가' })) as HTMLButtonElement
    await waitFor(() => expect(add.disabled).toBe(false))
    await user.click(add)
    await user.selectOptions(within(residence).getByRole('combobox', { name: '사는 곳 시도' }), '서울')
    await user.selectOptions(within(residence).getByRole('combobox', { name: '사는 곳 시군구' }), '마포구')
    expect(JSON.parse(localStorage.getItem(REGION_STORAGE_KEY)!).residence).toEqual([1114])
    expect(within(residence).getByText(/^이 브라우저에만 저장됐어요/)).toBeTruthy()

    await user.keyboard('{Escape}')
    const line = await folded()
    await waitFor(() => expect(conditionOf(line)).toBe('서울 마포구에 살아요'))
  })
})

describe('전국 and a failed account read', () => {
  it('no 전국 marker and no [내 도서관만] (user 2026-10-19)', async () => {
    guestRegions([1114])
    renderLine()
    await folded()
    expect(screen.queryByText(/전국에서 찾는 중/)).toBeNull()
    expect(screen.queryByRole('button', { name: '내 도서관만' })).toBeNull()
  })

  it('after login a failed read says so with [다시 시도], which reads it again (D7)', async () => {
    fake.signedIn = true
    fake.librariesStatus = 500
    const { user } = renderLine()
    const alert = await screen.findByRole('alert')
    expect(alert.textContent).toBe('내 도서관을 불러오지 못했어요 다시 시도')

    fake.librariesStatus = 200
    fake.libraryIds = [12]
    await user.click(within(alert).getByRole('button', { name: '다시 시도' }))
    const line = await folded()
    expect(conditionOf(line)).toBe('직접 추가한 도서관 1곳에서 찾아요')
  })
})

describe('the phone chip (R2-1)', () => {
  function renderChip() {
    const onOpen = vi.fn()
    render(
      <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
        <AuthProvider>
          <NoticeProvider>
            <MyLibraryProvider>
              <RegionProvider>
                <LibraryScopeChip
                  visit="any"
                  onVisitChange={vi.fn()}
                  planCount={null}
                  openCount={null}
                  onOpenCount={vi.fn()}
                  onOpen={onOpen}
                />
              </RegionProvider>
            </MyLibraryProvider>
          </NoticeProvider>
        </AuthProvider>
      </QueryClientProvider>,
    )
    return { onOpen, user: userEvent.setup() }
  }

  it('a first visitor: "지역 설정", which opens the sheet', async () => {
    const { onOpen, user } = renderChip()
    await user.click(await screen.findByRole('button', { name: '지역을 선택해주세요' }))
    expect(onOpen).toHaveBeenCalledTimes(1)
  })

  it('with regions: the words of the folded line', async () => {
    guestRegions([1114])
    renderChip()
    const chip = await screen.findByRole('button', { name: '지역을 선택해주세요' })
    await waitFor(() => expect(conditionOf(chip)).toBe('서울 마포구에 살아요'))
  })
})

/** User 2026-10-19: the chip opens its own sheet with only "찾을 도서관", not the ⚙ sheet. */
describe('the phone "찾을 도서관" sheet', () => {
  function renderChipWithSheet() {
    function Harness() {
      const [open, setOpen] = useState(false)
      return (
        <>
          <LibraryScopeChip
            visit="none"
            onVisitChange={vi.fn()}
            openCount={70}
            onOpenCount={vi.fn()}
            planCount={null}
            onOpen={() => setOpen(true)}
          />
          <LibraryScopeSheet open={open} onOpenChange={setOpen} />
        </>
      )
    }
    render(
      <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
        <AuthProvider>
          <NoticeProvider>
            <MyLibraryProvider>
              <RegionProvider>
                <MemoryRouter>
                  <Harness />
                </MemoryRouter>
              </RegionProvider>
            </MyLibraryProvider>
          </NoticeProvider>
        </AuthProvider>
      </QueryClientProvider>,
    )
    return userEvent.setup()
  }

  // only 거주 and the my page line since user 2026-10-20 (no 일하는 곳, 가입 방법 or [마이페이지에서 전체 보기])
  it('a dialog named "찾을 도서관" with 거주, a handle, ⓧ and the my page line, and no other settings', async () => {
    const user = renderChipWithSheet()
    await user.click(await screen.findByRole('button', { name: '지역을 선택해주세요' }))

    const sheet = within(await sheetDialog())
    expect(sheet.getByTestId('sheet-handle')).toBeTruthy()
    expect(sheet.getByRole('heading', { name: '거주' })).toBeTruthy()
    expect(sheet.queryByRole('heading', { name: '일하는 곳' })).toBeNull()
    expect(sheet.queryByRole('switch')).toBeNull()
    expect(sheet.queryByRole('heading', { name: '가입 방법' })).toBeNull()
    expect(sheet.queryByText(/어디 살든 가입되는 도서관/)).toBeNull()
    expect(sheet.queryByRole('link', { name: '마이페이지에서 전체 보기' })).toBeNull()
    expect(sheet.getByRole('link', { name: '마이페이지' }).getAttribute('href')).toBe('/my-libraries')
    expect(sheet.queryByRole('radiogroup')).toBeNull()

    await user.click(sheet.getByRole('button', { name: '닫기' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
  })

  it('the my page link closes the sheet', async () => {
    const user = renderChipWithSheet()
    await user.click(await screen.findByRole('button', { name: '지역을 선택해주세요' }))
    await user.click(within(await sheetDialog()).getByRole('link', { name: '마이페이지' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
  })
})
