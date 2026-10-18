import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { MemoryRouter } from 'react-router'
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import type { LibraryScope, VisitFilter } from '../../api/types'
import { AuthProvider } from '../auth/AuthContext'
import { NoticeProvider } from '../auth/NoticeContext'
import { MyLibraryProvider } from '../mylibrary/MyLibraryContext'
import { REGION_IMPORT_KEY } from '../mylibrary/regionImport'
import { RegionProvider } from '../region/RegionContext'
import { REGION_STORAGE_KEY } from '../region/regionStorage'
import { LibraryScopeChip, LibraryScopeLine, scopeLineText, type BoxFocus } from './LibraryScopeLine'
import { planCountKey, type PlanCount } from './searchSettings'

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
  scope?: LibraryScope
  allCount?: number | null
}

function renderLine(options: Options = {}) {
  const onSearchMine = vi.fn()
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
          scope={options.scope ?? 'joinable'}
          allCount={options.allCount ?? null}
          onSearchMine={onSearchMine}
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
  return { user: userEvent.setup(), onSearchMine, onVisitChange, onOpenCount }
}

const guestRegions = (residence: number[], work: number[] = []) =>
  localStorage.setItem(REGION_STORAGE_KEY, JSON.stringify({ residence, work }))
const folded = () => screen.findByRole('button', { name: /^내 도서관: / })
const box = () => screen.findByRole('dialog', { name: '찾을 도서관' })

describe('a first visitor (R1-2)', () => {
  it('only the button and the small text, no question line', async () => {
    renderLine()
    const button = await screen.findByRole('button', { name: '사는 곳·일하는 곳 고르기' })
    expect(button.closest('div')!.textContent).toBe('사는 곳·일하는 곳 고르기')
    expect(screen.getByText(/^고르면 그 지역 기준으로 찾아요/).textContent).toBe(
      '고르면 그 지역 기준으로 찾아요 · 안 고르면 어디 살든 가입되는 도서관에서 찾아요 · 일하는 곳도 더하기',
    )
    expect(screen.queryByText(/어디서 빌리실 건가요/)).toBeNull()
  })

  it('the button opens the box on the 사는 곳 grid; [일하는 곳도 더하기] opens it on the 일하는 곳 grid', async () => {
    const { user } = renderLine()
    await user.click(await screen.findByRole('button', { name: '사는 곳·일하는 곳 고르기' }))
    const opened = await box()
    expect(await within(opened).findByRole('list', { name: '사는 곳 시도' })).toBeTruthy()
    expect(within(opened).queryByRole('list', { name: '일하는 곳 시도' })).toBeNull()
    await user.keyboard('{Escape}')
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())

    await user.click(screen.getByRole('button', { name: '일하는 곳도 더하기' }))
    const work = await box()
    const workGroup = within(work).getByRole('group', { name: '일하는 곳' })
    expect(await within(workGroup).findByRole('list', { name: '일하는 곳 시도' })).toBeTruthy()
    await waitFor(() => expect(workGroup.contains(document.activeElement)).toBe(true))
  })
})

describe('the folded line (R3)', () => {
  it('adds only what differs from the default', () => {
    expect(scopeLineText({ residence: '서울 강남구', work: null, chosen: 0, visit: 'any', planCount: null })).toBe(
      '내 도서관: 서울 강남구',
    )
    expect(scopeLineText({ residence: '서울 강남구', work: '경기 성남시', chosen: 2, visit: 'none', planCount: 68 })).toBe(
      '내 도서관: 서울 강남구 · 일하는 곳 경기 성남시 · 방문 가입 뺌 · 직접 추가 2 · 68곳',
    )
    // 일하는 곳 only (user approval): it leads the line
    expect(scopeLineText({ residence: null, work: '경기 성남시', chosen: 0, visit: 'any', planCount: 12 })).toBe(
      '내 도서관: 일하는 곳 경기 성남시 · 12곳',
    )
    expect(scopeLineText({ residence: null, work: null, chosen: 3, visit: 'any', planCount: null })).toBe(
      '내 도서관: 직접 추가 3',
    )
    expect(scopeLineText({ residence: null, work: null, chosen: 0, visit: 'any', planCount: 39 })).toBeNull()
  })

  it('names this browser\'s regions, several of one 시도 joined by ·', async () => {
    guestRegions([1114, 1102], [4113])
    renderLine({ visit: 'none' })
    const line = await folded()
    await waitFor(() => expect(line.textContent).toBe('내 도서관: 서울 마포구·중구 · 일하는 곳 경기 성남시 · 방문 가입 뺌'))
  })

  it('work only: "내 도서관: 일하는 곳 …"', async () => {
    guestRegions([], [4113])
    renderLine()
    const line = await folded()
    await waitFor(() => expect(line.textContent).toBe('내 도서관: 일하는 곳 경기 성남시'))
  })

  it('"N곳" only while the kept count was taken with the same inputs (D5)', async () => {
    guestRegions([1114])
    const inputs = { source: 'browser' as const, residence: [1114], work: [], libraryIds: [], visit: 'any' as const }
    renderLine({ planCount: { key: planCountKey(inputs), count: 68 } })
    const line = await folded()
    await waitFor(() => expect(line.textContent).toBe('내 도서관: 서울 마포구 · 68곳'))
    cleanup()

    // another 방문 가입 빼기, another region, or the same regions taken after login: no number
    for (const other of [{ ...inputs, visit: 'none' as const }, { ...inputs, residence: [1102] }, { ...inputs, source: 'account' as const }]) {
      renderLine({ planCount: { key: planCountKey(other), count: 68 } })
      const plain = await folded()
      await waitFor(() => expect(plain.textContent).toBe('내 도서관: 서울 마포구'))
      cleanup()
    }
  })
})

describe('the box (R1-4)', () => {
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
      '상세검색이나 마이페이지에서 이름으로 더할 수 있어요',
      '어디 살든 가입되는 도서관 39곳은 늘 들어 있어요',
      '마이페이지에서 전체 보기',
    ]
    expect(parts.filter((text) => order.includes(text ?? ''))).toEqual(order)
    const visit = within(opened).getByRole('checkbox', { name: '직접 가야 가입되는 도서관은 빼기' })
    const addWork = within(opened).getByRole('button', { name: '일하는 곳도 더하기' })
    const openLine = within(opened).getByText(/늘 들어 있어요$/)
    expect(addWork.compareDocumentPosition(visit) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    expect(visit.compareDocumentPosition(openLine) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()

    await user.click(screen.getByText('outside'))
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
    expect(within(opened).getByText('어디 살든 가입되는 도서관은 늘 들어 있어요')).toBeTruthy()
    await user.click(within(opened).getByRole('checkbox', { name: '직접 가야 가입되는 도서관은 빼기' }))
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
    await user.click(await screen.findByRole('button', { name: '사는 곳·일하는 곳 고르기' }))
    const opened = await box()
    await user.click(await within(opened).findByRole('checkbox', { name: '마포구' }))
    expect(JSON.parse(localStorage.getItem(REGION_STORAGE_KEY)!).residence).toEqual([1114])
    const residence = within(opened).getByRole('group', { name: '사는 곳' })
    expect(within(residence).getByText(/^이 브라우저에만 저장됐어요/)).toBeTruthy()
    expect(within(within(opened).getByRole('group', { name: '일하는 곳' })).queryByText(/이 브라우저에만/)).toBeNull()

    await user.keyboard('{Escape}')
    const line = await folded()
    await waitFor(() => expect(line.textContent).toBe('내 도서관: 서울 마포구'))
  })
})

describe('전국 and a failed account read', () => {
  it('a scope=all search: the amber marker with its count and [내 도서관만] (R1-5, R4-2)', async () => {
    guestRegions([1114])
    const { user, onSearchMine } = renderLine({ scope: 'all', allCount: 376 })
    await folded()
    expect(screen.getByText('전국에서 찾는 중 · 376곳')).toBeTruthy()
    await user.click(screen.getByRole('button', { name: '내 도서관만' }))
    expect(onSearchMine).toHaveBeenCalledTimes(1)
  })

  it('no marker for the usual search', async () => {
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
    expect(line.textContent).toBe('내 도서관: 직접 추가 1')
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
                  scope="joinable"
                  allCount={null}
                  onSearchMine={vi.fn()}
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

  it('a first visitor: "사는 곳·일하는 곳 고르기", which opens the sheet', async () => {
    const { onOpen, user } = renderChip()
    await user.click(await screen.findByRole('button', { name: '사는 곳·일하는 곳 고르기' }))
    expect(onOpen).toHaveBeenCalledTimes(1)
  })

  it('with regions: the words of the folded line', async () => {
    guestRegions([1114])
    renderChip()
    const chip = await screen.findByRole('button', { name: /^내 도서관: / })
    await waitFor(() => expect(chip.textContent).toBe('내 도서관: 서울 마포구'))
  })
})
