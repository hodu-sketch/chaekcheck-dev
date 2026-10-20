import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
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
const folded = () => screen.findByRole('button', { name: '바꾸기' })
const box = () => screen.findByRole('dialog', { name: '찾을 도서관' })

describe('a first visitor (R1-2)', () => {
  it('the condition card with [사는 곳 고르기], and the small grey line under the card (user 2026-10-20 words)', async () => {
    renderLine()
    const button = await screen.findByRole('button', { name: '사는 곳 고르기' })
    const card = screen.getByTestId('scope-condition')
    expect(card.textContent).toBe('사는 곳을 알려 주시면 가입할 수 있는 도서관을 골라 드려요사는 곳 고르기')
    expect(conditionOf(button)).toBe('사는 곳을 알려 주시면 가입할 수 있는 도서관을 골라 드려요')
    const hint = screen.getByText('안 골라도 어디 살든 가입되는 도서관에서 찾아요 · 일하는 곳도 더할 수 있어요')
    expect(card.contains(hint)).toBe(false)
    expect(screen.queryByText(/어디서 빌리실 건가요/)).toBeNull()
  })

  it('with regions: [조건 수정] and no grey line', async () => {
    guestRegions([1114])
    renderLine()
    const button = await folded()
    await waitFor(() => expect(conditionOf(button)).toBe('서울 마포구에 살아요'))
    expect(screen.getByTestId('scope-condition').textContent).toBe('서울 마포구에 살아요바꾸기')
    expect(screen.queryByText(/안 골라도/)).toBeNull()
  })

  it('the button opens the box; no [일하는 곳도 더하기] beside it (user 2026-10-19)', async () => {
    const { user } = renderLine()
    expect(screen.queryByRole('button', { name: '일하는 곳도 더하기' })).toBeNull()
    await user.click(await screen.findByRole('button', { name: '사는 곳 고르기' }))
    const opened = await box()
    expect(within(opened).getByRole('group', { name: '사는 곳' })).toBeTruthy()
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
    const button = await screen.findByRole('button', { name: '사는 곳 고르기' })
    expect(conditionOf(button)).toBe('어디 살든 가입되는 도서관 41곳에서 찾았어요')
    expect(screen.getByText('사는 곳을 알려 주시면 그 지역 도서관도 보여요')).toBeTruthy()
    cleanup()

    renderLine({ searched: searched({ planCount: null }) })
    expect(conditionOf(await screen.findByRole('button', { name: '사는 곳 고르기' }))).toBe(
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

describe('the box (R1-4)', () => {
  it('starts with the one line 왜 묻나요 (ADR-0139 #3)', async () => {
    const { user } = renderLine()
    await user.click(await screen.findByRole('button', { name: '사는 곳 고르기' }))
    const opened = await box()
    expect(within(opened).getByText('왜 묻나요? 도서관마다 가입 조건이 달라서요.')).toBeTruthy()
  })

  it('holds the parts in order and closes on a click outside and on Esc', async () => {
    guestRegions([1114])
    const { user } = renderLine({ openCount: 39 })
    await user.click(await folded())
    const opened = await box()
    const parts = Array.from(
      opened.querySelectorAll('h3, input[type="checkbox"]:not([value]), p, a, button'),
    ).map((element) => element.textContent)
    const order = [
      '사는 곳',
      '일하는 곳',
      '일하는 곳도 더하기',
      '직접 추가 0곳',
      '마이페이지에서 이름으로 더할 수 있어요',
      '+ 어디 살든 가입되는 도서관 39곳도 늘 같이 찾아요',
      '가입 방법',
      '마이페이지에서 전체 보기',
    ]
    expect(parts.filter((text) => order.includes(text ?? ''))).toEqual(order)
    // the 방문 가입 switch is in its own "가입 방법" part, after the 어디 살든 가입 note (user 2026-10-19)
    const visit = within(opened).getByRole('switch', { name: '온라인에서 가입 가능한 곳만 보기' })
    const openLine = within(opened).getByText(/늘 같이 찾아요$/)
    expect(openLine.compareDocumentPosition(visit) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    expect(within(opened).getByRole('group', { name: '가입 방법' }).contains(visit)).toBe(true)
    // a switch with its small line (user 2026-10-19)
    expect(
      within(opened).getByRole('switch', {
        name: '온라인에서 가입 가능한 곳만 보기',
        description: '도서관에 직접 가야 가입되는 곳은 결과에서 빼요',
      }),
    ).toBe(visit)

    // a modal on wider screens (user 2026-10-19): the page under the overlay takes no pointer, so a press outside is
    // sent directly (the way radix's outside-press listener sees it)
    fireEvent.pointerDown(screen.getByText('outside'))
    fireEvent.click(screen.getByText('outside'))
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())

    await user.click(await folded())
    await box()
    await user.keyboard('{Escape}')
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
  })

  it('the 어디 살든 가입 line goes without a number it does not know; the switch only reports the choice', async () => {
    guestRegions([1114])
    const { user, onVisitChange } = renderLine()
    await user.click(await folded())
    const opened = await box()
    expect(within(opened).getByText('+ 어디 살든 가입되는 도서관도 늘 같이 찾아요')).toBeTruthy()
    await user.click(within(opened).getByRole('switch', { name: '온라인에서 가입 가능한 곳만 보기' }))
    expect(onVisitChange).toHaveBeenCalledWith('none')
  })

  it('names the added libraries from the list without regions, read only for them; a missing one is 꺼진 도서관', async () => {
    localStorage.setItem('chaekcheck.chosenLibraries.v1', JSON.stringify([999, 12, 207]))
    const { user, onOpenCount } = renderLine()
    await user.click(await folded())
    const opened = await box()
    expect(within(opened).getByRole('heading', { name: '직접 추가 3곳' })).toBeTruthy()
    expect(await within(opened).findByText('꺼진 도서관, 마포구립 전자도서관 외 1곳')).toBeTruthy()
    expect(fake.libraryLists).toBe(1)
    // the list's OPEN rows are the number of 어디 살든 가입 libraries (R3-4)
    expect(onOpenCount).toHaveBeenCalledWith(2)
  })

  it('without added libraries the list is not read (D4)', async () => {
    guestRegions([1114])
    const { user } = renderLine()
    await user.click(await folded())
    await box()
    expect(fake.libraryLists).toBe(0)
  })

  it('before login a check is saved in this browser and the save hint shows under 사는 곳 (R9)', async () => {
    const { user } = renderLine()
    await user.click(await screen.findByRole('button', { name: '사는 곳 고르기' }))
    const opened = await box()
    const residence = within(opened).getByRole('group', { name: '사는 곳' })
    const add = (await within(residence).findByRole('button', { name: '사는 곳 추가' })) as HTMLButtonElement
    await waitFor(() => expect(add.disabled).toBe(false))
    await user.click(add)
    await user.selectOptions(within(residence).getByRole('combobox', { name: '사는 곳 시도' }), '서울')
    await user.selectOptions(within(residence).getByRole('combobox', { name: '사는 곳 시군구' }), '마포구')
    expect(JSON.parse(localStorage.getItem(REGION_STORAGE_KEY)!).residence).toEqual([1114])
    expect(within(residence).getByText(/^이 브라우저에만 저장됐어요/)).toBeTruthy()
    expect(within(within(opened).getByRole('group', { name: '일하는 곳' })).queryByText(/이 브라우저에만/)).toBeNull()

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
    await user.click(await screen.findByRole('button', { name: '사는 곳 고르기' }))
    expect(onOpen).toHaveBeenCalledTimes(1)
  })

  it('with regions: the words of the folded line', async () => {
    guestRegions([1114])
    renderChip()
    const chip = await screen.findByRole('button', { name: '바꾸기' })
    await waitFor(() => expect(conditionOf(chip)).toBe('서울 마포구에 살아요'))
  })
})

/** User 2026-10-19: the chip opens its own sheet with only "찾을 도서관", not the ⚙ sheet. */
describe('the phone "찾을 도서관" sheet', () => {
  function renderChipWithSheet() {
    function Harness() {
      const [open, setOpen] = useState(false)
      const common = {
        visit: 'any' as const,
        onVisitChange: vi.fn(),
        openCount: 70,
        onOpenCount: vi.fn(),
      }
      return (
        <>
          <LibraryScopeChip
            {...common}
            planCount={null}
            onOpen={() => setOpen(true)}
          />
          <LibraryScopeSheet {...common} open={open} onOpenChange={setOpen} focus="residence" />
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

  it('a dialog named "찾을 도서관" with the box body, a handle and ⓧ, and no other settings', async () => {
    const user = renderChipWithSheet()
    await user.click(await screen.findByRole('button', { name: '사는 곳 고르기' }))

    const sheet = within(await box())
    expect(sheet.getByTestId('sheet-handle')).toBeTruthy()
    expect(sheet.getByRole('heading', { name: '사는 곳' })).toBeTruthy()
    expect(sheet.getByRole('heading', { name: '일하는 곳' })).toBeTruthy()
    expect(sheet.getByRole('switch', { name: '온라인에서 가입 가능한 곳만 보기' })).toBeTruthy()
    expect(sheet.getByText('+ 어디 살든 가입되는 도서관 70곳도 늘 같이 찾아요')).toBeTruthy()
    expect(sheet.getByRole('heading', { name: '가입 방법' })).toBeTruthy()
    expect(sheet.getByRole('link', { name: '마이페이지에서 전체 보기' })).toBeTruthy()
    expect(sheet.queryByRole('radiogroup')).toBeNull()

    await user.click(sheet.getByRole('button', { name: '닫기' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
  })
})
