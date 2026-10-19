import { cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router'
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest'
import type { TargetLibrary } from '../../api/types'
import { QUEUED_LABEL } from '../lookupstatus/lookupStatusText'
import {
  HOLDING_GONE,
  LOOKUP_UNCHECKED,
  MOBILE_SITE_NOTE,
  ResultRow,
  TAG_TEXT,
  UncheckedLibraryLine,
  type RegionBasis,
} from './ResultRow'
import { testRow } from './testRows'

// the header light's queued libraries (cycle 20), set per test
const lookup = { queued: new Set<number>() }
vi.mock('../lookupstatus/LookupStatusProvider', () => ({ useLookupStatus: () => ({ view: null, queued: lookup.queued }) }))

afterEach(cleanup)

beforeAll(() => {
  // Radix positions popovers with ResizeObserver, which jsdom does not have
  globalThis.ResizeObserver ??= class {
    observe() {}
    unobserve() {}
    disconnect() {}
  }
})

const openLibrary: TargetLibrary = {
  id: 2,
  name: '고흥군립도서관 전자도서관',
  libraryType: 'EBOOK',
  platformType: 'KYOBO_V1',
  priorityGroup: 'OPEN',
  deferred: false,
  extraConditionText: null,
  visitRequired: false,
  eligibility: 'OPEN',
  eligibilitySummary: null,
  joinMethod: '도서관 홈페이지 가입 후 로그인',
  joinUrl: null,
  joinCaution: null,
  joinNotice: null,
  homepageUrl: null,
  chosen: false,
  joinSurvey: 'SURVEYED',
  logoUrl: null,
  mobileSiteDiffers: false,
}

/** The join details open from "ⓘ 가입 정보" on every width (user request 2026-10-05). */
async function renderOpen(library: TargetLibrary) {
  render(<ResultRow row={testRow({ libraryId: 2, rank: 1 })} library={library} searchUrl={undefined} />)
  await userEvent.setup().click(screen.getByRole('button', { name: `${library.name} 가입 정보` }))
  return screen.getByRole('dialog')
}

describe('join guide behind "가입 정보"', () => {
  it('is not drawn until the button is pressed', () => {
    render(<ResultRow row={testRow({ libraryId: 2, rank: 1 })} library={openLibrary} searchUrl={undefined} />)

    expect(screen.queryByText('어디 살든 가입')).toBeNull()
    expect(screen.queryByText(/도서관 홈페이지 가입 후 로그인/)).toBeNull()
    expect(screen.getByRole('button', { name: `${openLibrary.name} 가입 정보` })).toBeTruthy()
  })
})

describe('ResultRow join caution (C2-U-11)', () => {
  // the join guide (user 2026-10-19): "어디 살든 가입" as the target, the caution as "유의" under the steps
  it('shows the caution with "어디 살든 가입" without the "가입 자격 확인 필요" badge', async () => {
    const popover = await renderOpen({ ...openLibrary, joinCaution: '회원증 발급은 고흥군 거주자·직장인만' })

    expect(popover.textContent).toContain('어디 살든 가입')
    expect(popover.textContent).toContain('유의 회원증 발급은 고흥군 거주자·직장인만')
    expect(screen.queryByText('가입 자격 확인 필요')).toBeNull()
  })

  it('shows a notice as "안내" without the caution mark', async () => {
    const popover = await renderOpen({ ...openLibrary, joinNotice: '시각장애인 이용 도서관' })

    expect(popover.textContent).toContain('안내')
    expect(popover.textContent).toContain('시각장애인 이용 도서관')
    expect(popover.textContent).not.toContain('유의')
  })

  it('a matching region with a 책이음 text is joinable; the text is only shown (AC-12)', async () => {
    const popover = await renderOpen({
      ...openLibrary,
      priorityGroup: 'MY_SIGUNGU',
      eligibility: 'ELIGIBLE',
      eligibilitySummary: '서산시 거주·재직·재학',
      extraConditionText: '공공도서관 책이음서비스 회원인 사람도 가입 대상',
    })

    expect(popover.textContent).toContain('책이음서비스 회원인 사람도')
    expect(screen.queryByText('가입 자격 확인 필요')).toBeNull()
  })

  it("the same library without the user's region shows its summary as the target (AC-12)", async () => {
    const library: TargetLibrary = {
      ...openLibrary,
      priorityGroup: 'NEEDS_CHECK',
      eligibility: 'UNVERIFIED',
      eligibilitySummary: '서산시 거주·재직·재학',
      extraConditionText: '공공도서관 책이음서비스 회원인 사람도 가입 대상',
    }
    render(<ResultRow row={testRow({ libraryId: 2, rank: 1 })} library={library} searchUrl={undefined} />)
    // not next to the name any more: it opens with the name (user request 2026-10-05)
    expect(screen.queryByText('가입 자격 확인 필요')).toBeNull()

    await userEvent.setup().click(screen.getByRole('button', { name: `${library.name} 가입 정보` }))

    const guide = screen.getByRole('dialog')
    expect(guide.textContent).toContain('가입 대상서산시 거주·재직·재학')
    expect(guide.textContent).toContain('책이음서비스 회원인 사람도')
  })

  it('shows the "방문 가입" tag for a visit-only library', async () => {
    const popover = await renderOpen({ ...openLibrary, visitRequired: true })

    expect(popover.textContent).toContain('방문 가입')
  })

  it('shows no caution line when there is none', async () => {
    const popover = await renderOpen(openLibrary)

    expect(popover.textContent).not.toContain('유의')
  })
})

describe('a library added by hand (cycle 06, AC-5)', () => {
  const chosenLibrary: TargetLibrary = {
    ...openLibrary,
    priorityGroup: 'QUALIFIED',
    eligibility: 'UNKNOWN',
    eligibilitySummary: '부산 거주',
    extraConditionText: '책이음 회원 가입 가능',
    joinCaution: '회원증 발급은 부산 시민만',
    chosen: true,
  }

  it('shows "직접 추가" in place of the eligibility badge', () => {
    render(<ResultRow row={testRow({ libraryId: 2, rank: 1 })} library={chosenLibrary} searchUrl={undefined} />)

    expect(screen.getByText('직접 추가')).toBeTruthy()
    expect(screen.queryByText('가입 자격 확인 필요')).toBeNull()
  })

  it('shows "직접 추가한 도서관", the extra condition, caution and how to join from the same button', async () => {
    const popover = await renderOpen(chosenLibrary)

    expect(popover.textContent).toContain('가입 대상직접 추가한 도서관')
    expect(popover.textContent).not.toContain('부산 거주')
    expect(popover.textContent).toContain('책이음 회원 가입 가능')
    expect(popover.textContent).toContain('회원증 발급은 부산 시민만')
    expect(popover.textContent).toContain('도서관 홈페이지 가입 후 로그인')
  })

  it('keeps the "방문 가입" tag', async () => {
    const popover = await renderOpen({ ...openLibrary, visitRequired: true, chosen: true })

    expect(popover.textContent).toContain('방문 가입')
  })
})

describe('subscription rows (user request 2026-10-05)', () => {
  it('show the monthly budget caution only from the state', async () => {
    render(
      <ResultRow
        row={testRow({ libraryId: 2, rank: 1, availability: 'AVAILABLE', availabilityBasis: 'SUBSCRIPTION' })}
        library={{ ...openLibrary, platformType: 'KYOBO_SUBSCRIPTION_V1' }}
        searchUrl={undefined}
      />,
    )
    expect(screen.queryByText('월 예산 소진 시 이용 불가')).toBeNull()

    await userEvent.setup().click(screen.getByRole('button', { name: /구독형 · 바로 대출/ }))

    expect(screen.getByRole('dialog').textContent).toContain('월 예산 소진 시 이용 불가')
  })
})


describe('the card opens the library (user request 2026-10-05)', () => {
  it('is one link to the book page, with no separate button', () => {
    render(
      <ResultRow
        row={testRow({ libraryId: 2, rank: 1, detailUrl: 'https://www.ghlib.go.kr:446/book/1' })}
        library={openLibrary}
        searchUrl="https://www.ghlib.go.kr:446/search"
      />,
    )

    const links = screen.getAllByRole('link')
    expect(links).toHaveLength(1)
    expect(links[0].getAttribute('href')).toBe('https://www.ghlib.go.kr:446/book/1')
    expect(links[0].getAttribute('target')).toBe('_blank')
    expect(links[0].textContent).toContain(`${openLibrary.name}에서 보기`)
    // the library name still opens its join details, not the link
    expect(screen.getByRole('button', { name: `${openLibrary.name} 가입 정보` })).toBeTruthy()
  })

  it('is not a link when the site gave no address', () => {
    render(<ResultRow row={testRow({ libraryId: 2, rank: 1, detailUrl: null })} library={openLibrary} searchUrl={undefined} />)

    expect(screen.queryByRole('link')).toBeNull()
  })
})

/** Cycle 07 (R6, AC-14): a collected-list library's notice by its join research, then its front page. */
describe('join notice of libraries found in the collected list', () => {
  const collected: TargetLibrary = {
    ...openLibrary,
    id: 2,
    // cycle 09: no "(북큐브)" in the name any more; the vendor comes as its own value
    name: '강동대학교',
    vendor: 'BOOKCUBE',
    lendingModel: 'OWNED',
    eligibility: 'UNSURVEYED',
    joinMethod: null,
    homepageUrl: 'https://gangdong.bookcube.biz/FxLibrary',
    chosen: true,
  }

  it.each([
    ['NOT_FOUND', '가입 조건을 찾지 못했어요. 도서관 사이트에서 확인해 주세요'],
    ['MEMBERS_ONLY', '학교·회사 같은 기관의 구성원용으로 보여요. 이용할 수 있는지는 그 기관에 확인해 주세요'],
    ['NOT_SURVEYED', '가입 정보를 아직 조사하지 않았어요'],
  ] as const)('%s shows its notice and the front page as the official link', async (joinSurvey, notice) => {
    const popover = await renderOpen({ ...collected, joinSurvey })

    expect(popover.textContent).toContain(notice)
    expect(popover.textContent).not.toContain('가입 자격 확인 필요')
    const link = within(popover).getByRole('link', { name: /공식 가입 안내 보기/ })
    expect(link.getAttribute('href')).toBe('https://gangdong.bookcube.biz/FxLibrary')
    expect(link.getAttribute('rel')).toBe('noopener noreferrer')
  })

  it('a surveyed one shows the usual join details', async () => {
    const popover = await renderOpen({ ...collected, joinSurvey: 'SURVEYED', eligibility: 'OPEN', chosen: false })

    expect(popover.textContent).toContain('어디 살든 가입')
    expect(popover.textContent).not.toContain('가입 정보를 아직 조사하지 않았어요')
  })

  it('draws the name as text only', () => {
    render(
      <ResultRow
        row={testRow({ libraryId: 2, rank: 1 })}
        library={{ ...collected, joinSurvey: 'NOT_SURVEYED', name: '<b>학교</b>' }}
        searchUrl={undefined}
      />,
    )

    expect(screen.getByRole('button', { name: '<b>학교</b> 가입 정보' }).textContent).toBe('<b>학교</b>')
  })
})

/** Cycle 07 (ADR-0059, AC-20): the logo before the name opens the library, not the book. */
describe('library logo on the card', () => {
  it('opens the library front page apart from the card link', () => {
    render(
      <ResultRow
        row={testRow({ libraryId: 2, rank: 1, detailUrl: 'https://lib.test/book/1' })}
        library={{ ...openLibrary, logoUrl: '/library-logos/2.png', homepageUrl: 'https://lib.test/main' }}
        searchUrl={undefined}
      />,
    )

    const logo = screen.getByRole('img', { name: openLibrary.name })
    expect(logo.getAttribute('src')).toBe('/library-logos/2.png')
    expect(logo.closest('a')?.getAttribute('href')).toBe('https://lib.test/main')
    expect(screen.getByRole('link', { name: /에서 보기/ }).getAttribute('href')).toBe('https://lib.test/book/1')
  })
})

/** Cycle 08 R3 (AC-4): the phone note of the 13 sites whose phone version shows another page. */
describe('phone site note', () => {
  function phoneWidth(phone: boolean) {
    window.matchMedia = ((query: string) => ({
      matches: phone && query === '(width <= 640px)',
      media: query,
      addEventListener: () => {},
      removeEventListener: () => {},
    })) as unknown as typeof window.matchMedia
  }

  afterEach(() => {
    // jsdom has no matchMedia: back to "not a phone"
    delete (window as { matchMedia?: unknown }).matchMedia
  })

  it('shows under the name on a phone only, for a flagged library only, and keeps the card link', () => {
    phoneWidth(true)
    const row = testRow({ libraryId: 2, rank: 1, detailUrl: 'https://lib.test/book/1' })
    render(<ResultRow row={row} library={{ ...openLibrary, mobileSiteDiffers: true }} searchUrl={undefined} />)
    expect(screen.getByText(MOBILE_SITE_NOTE)).toBeTruthy()
    expect(screen.getByRole('link', { name: /에서 보기/ }).getAttribute('href')).toBe('https://lib.test/book/1')
    cleanup()

    render(<ResultRow row={row} library={openLibrary} searchUrl={undefined} />)
    expect(screen.queryByText(MOBILE_SITE_NOTE)).toBeNull()
    cleanup()

    phoneWidth(false)
    render(<ResultRow row={row} library={{ ...openLibrary, mobileSiteDiffers: true }} searchUrl={undefined} />)
    expect(screen.queryByText(MOBILE_SITE_NOTE)).toBeNull()
  })

  it('shows on a failed check too, and in the join guide sheet', async () => {
    phoneWidth(true)
    render(
      <ResultRow
        row={testRow({ libraryId: 2, rank: 1, failed: true })}
        library={{ ...openLibrary, joinSurvey: 'NOT_FOUND', homepageUrl: 'https://lib.test/main', mobileSiteDiffers: true }}
        searchUrl={undefined}
      />,
    )
    expect(screen.getAllByText(MOBILE_SITE_NOTE)).toHaveLength(1)
    await userEvent.setup().click(screen.getByRole('button', { name: `${openLibrary.name} 가입 정보` }))
    const popover = screen.getByRole('dialog')
    expect(popover.textContent).toContain('공식 가입 안내 보기')
    expect(popover.textContent).toContain(MOBILE_SITE_NOTE)
  })
})

/** Cycle 09 (handoff step 5-3): "교보 · 소장" on the line after the name, every width, outside the name button. */
describe('library classification on the card', () => {
  function renderWith(library: TargetLibrary) {
    render(<ResultRow row={testRow({ libraryId: 2, rank: 1 })} library={library} searchUrl={undefined} />)
    return screen.getByRole('button', { name: `${library.name} 가입 정보` })
  }

  it('is the line right after the name, not inside the name button, before the phone note', () => {
    window.matchMedia = ((query: string) => ({
      matches: query === '(width <= 640px)',
      media: query,
      addEventListener: () => {},
      removeEventListener: () => {},
    })) as unknown as typeof window.matchMedia
    try {
      const button = renderWith({ ...openLibrary, vendor: 'KYOBO', lendingModel: 'OWNED', orderNo: 1, mobileSiteDiffers: true })

      expect(button.textContent).toBe(openLibrary.name)
      const line = screen.getByText('교보 · 소장')
      expect(line.tagName).toBe('P')
      expect(button.contains(line)).toBe(false)
      const nameLine = button.closest('p')!
      expect(nameLine.nextElementSibling).toBe(line)
      expect(line.nextElementSibling?.textContent).toBe(MOBILE_SITE_NOTE)
    } finally {
      delete (window as { matchMedia?: unknown }).matchMedia
    }
  })

  it('a portal is "통합 포털"', () => {
    renderWith({ ...openLibrary, platformType: 'ELIB_PORTAL_V1', vendor: 'PORTAL', lendingModel: null, orderNo: 1 })

    expect(screen.getByText('통합 포털')).toBeTruthy()
  })

  it('a second site adds "· 2번째 사이트" (AC-9)', () => {
    renderWith({ ...openLibrary, vendor: 'YES24', lendingModel: 'OWNED', orderNo: 2 })

    expect(screen.getByText('YES24 · 소장 · 2번째 사이트')).toBeTruthy()
  })

  it('makes no line without the values (an older server)', () => {
    const button = renderWith(openLibrary)

    expect(button.closest('p')!.nextElementSibling).toBeNull()
    expect(screen.queryByText(/소장|구독|통합 포털/)).toBeNull()
  })
})

/** Cycle 08 (ADR-0070, AC-8c): a holding the last import dropped. */
describe('holding that left the list', () => {
  it('says so in place of the failed row text', () => {
    render(<ResultRow row={testRow({ libraryId: 2, rank: 1, failed: true, gone: true })} library={openLibrary} searchUrl={undefined} />)
    expect(screen.getByText(HOLDING_GONE)).toBeTruthy()
    expect(screen.queryByText(LOOKUP_UNCHECKED)).toBeNull()
  })
})

/** Cycle 20 handoff 1-13 [확정]: the failed row's text, and 「밀려요」 (spec R4, review #5, AC-5). */
describe('unchecked rows and the queued mark (cycle 20)', () => {
  afterEach(() => {
    lookup.queued = new Set()
  })

  it('a failed row says 「대출 정보를 확인하지 못했어요」 in the unchecked group', () => {
    render(<ResultRow row={testRow({ libraryId: 2, rank: 1, failed: true, availability: 'UNKNOWN' })} library={openLibrary} searchUrl={undefined} />)
    expect(screen.getByText(LOOKUP_UNCHECKED)).toBeTruthy()
    expect(screen.queryByText('확인 실패')).toBeNull()
    expect(screen.getByRole('article').getAttribute('data-group')).toBe('unchecked')
  })

  // user request 2026-10-18: the card still opens the book's page at the library when its answer did not come
  it('a failed row with the book page links the card there', () => {
    const row = testRow({ libraryId: 2, rank: 1, failed: true, availability: 'UNKNOWN', detailUrl: 'https://lib.test/book/9' })
    render(<ResultRow row={row} library={openLibrary} searchUrl={undefined} />)
    expect(screen.getByText(LOOKUP_UNCHECKED)).toBeTruthy()
    expect(screen.getByRole('link', { name: /에서 보기/ }).getAttribute('href')).toBe('https://lib.test/book/9')
  })

  it('marks a row still being checked and an unchecked row of a queued library, never an answered one (AC-5)', () => {
    lookup.queued = new Set([2])
    const { rerender } = render(
      <ResultRow row={testRow({ libraryId: 2, rank: 1, availability: 'PENDING' })} library={openLibrary} searchUrl={undefined} />,
    )
    expect(screen.getByText(QUEUED_LABEL)).toBeTruthy()
    rerender(<ResultRow row={testRow({ libraryId: 2, rank: 1, availability: 'AVAILABLE' })} library={openLibrary} searchUrl={undefined} />)
    expect(screen.queryByText(QUEUED_LABEL)).toBeNull()
    rerender(<ResultRow row={testRow({ libraryId: 2, rank: 1, availability: 'UNKNOWN', failed: true })} library={openLibrary} searchUrl={undefined} />)
    expect(screen.getByText(QUEUED_LABEL)).toBeTruthy()
  })

  it('does not mark a library that is not queued', () => {
    lookup.queued = new Set([9])
    render(<ResultRow row={testRow({ libraryId: 2, rank: 1, availability: 'PENDING' })} library={openLibrary} searchUrl={undefined} />)
    expect(screen.queryByText(QUEUED_LABEL)).toBeNull()
  })

  it('keeps the mark on a failed row after the library left the queue, until the next search', () => {
    lookup.queued = new Set([2])
    const row = testRow({ libraryId: 2, rank: 1, availability: 'UNKNOWN', failed: true })
    const { rerender } = render(<ResultRow row={row} library={openLibrary} searchUrl={undefined} />)
    expect(screen.getByText(QUEUED_LABEL)).toBeTruthy()
    lookup.queued = new Set()
    rerender(<ResultRow row={{ ...row }} library={openLibrary} searchUrl={undefined} />)
    expect(screen.getByText(QUEUED_LABEL)).toBeTruthy()
  })

  it('a direct search library that failed is one line with the text and a link to its site (review #1)', () => {
    lookup.queued = new Set([2])
    render(<UncheckedLibraryLine library={openLibrary} url="https://lib.example/" />)
    const line = screen.getByRole('article')
    expect(line.textContent).toContain(`${openLibrary.name} · ${LOOKUP_UNCHECKED}`)
    expect(line.textContent).toContain(QUEUED_LABEL)
    expect(screen.getByRole('link').getAttribute('href')).toBe('https://lib.example/')
  })
})

/** Cycle 22 R6 (D1, D2, ADR-0136): the pills after the library name and their notes. */
describe('pills after the library name', () => {
  function renderTags(library: TargetLibrary, tags = true, regionBasis: RegionBasis = 'both') {
    render(
      <MemoryRouter>
        <ResultRow
          row={testRow({ libraryId: library.id, rank: 1 })}
          library={library}
          searchUrl={undefined}
          tags={tags}
          regionBasis={regionBasis}
        />
      </MemoryRouter>,
    )
    return userEvent.setup()
  }
  const mine: TargetLibrary = { ...openLibrary, priorityGroup: 'MY_SIGUNGU', eligibility: 'ELIGIBLE' }
  const pill = (name: string) => screen.getByRole('button', { name })

  it('[어디 살든 가입] opens its note; "가입 방법 보기" opens the join guide', async () => {
    const user = renderTags(openLibrary)
    await user.click(pill('어디 살든 가입'))
    const note = screen.getByRole('dialog')
    expect(note.textContent).toBe(`${TAG_TEXT.open} · 가입 방법 보기`)
    await user.click(within(note).getByRole('button', { name: '가입 방법 보기' }))
    const join = screen.getByRole('dialog', { name: `${openLibrary.name} 가입 안내` })
    expect(join.textContent).toContain('어디 살든 가입')
    expect(join.textContent).toContain('도서관 홈페이지 가입 후 로그인')
  })

  it('[방문 가입] in amber next to the group pill, naming the library', async () => {
    const user = renderTags({ ...openLibrary, visitRequired: true })
    expect(pill('어디 살든 가입')).toBeTruthy()
    expect(pill('방문 가입').getAttribute('data-tone')).toBe('visit')
    await user.click(pill('방문 가입'))
    expect(screen.getByRole('dialog').textContent).toBe(
      `가입하려면 ${openLibrary.name}에 한 번 가야 해요 · 가입 방법 보기`,
    )
  })

  it.each([
    ['residence', '사는 곳으로 가입할 수 있어요 · 가입 방법 보기'],
    ['work', '일하는 곳으로 가입할 수 있어요 · 가입 방법 보기'],
    ['both', '사는 곳이나 일하는 곳으로 가입할 수 있어요 · 가입 방법 보기'],
  ] as const)('[내 지역] says the side the search used (%s)', async (basis, text) => {
    const user = renderTags(mine, true, basis)
    await user.click(pill('내 지역'))
    expect(screen.getByRole('dialog').textContent).toBe(text)
  })

  it('[직접 추가] leads to the my page, in place of the old mark', async () => {
    const user = renderTags({ ...mine, chosen: true })
    expect(screen.queryByRole('button', { name: '내 지역' })).toBeNull()
    await user.click(pill('직접 추가'))
    const note = screen.getByRole('dialog')
    expect(note.textContent).toBe('직접 추가한 도서관이에요 · 마이페이지에서 빼기')
    expect(within(note).getByRole('link', { name: '마이페이지에서 빼기' }).getAttribute('href')).toBe('/my-libraries')
  })

  it('③ shows the eligibility summary in grey, without a note', () => {
    renderTags({ ...openLibrary, priorityGroup: 'QUALIFIED', eligibility: 'UNKNOWN', eligibilitySummary: '부산 거주·직장' })
    expect(screen.getByText('부산 거주·직장').tagName).toBe('SPAN')
    expect(screen.queryByRole('button', { name: /어디 살든 가입|내 지역|직접 추가/ })).toBeNull()
  })

  it('opens on a computer when the mouse rests on the pill, and closes when it leaves', async () => {
    renderTags(openLibrary)
    fireEvent.pointerEnter(pill('어디 살든 가입'), { pointerType: 'mouse' })
    expect(screen.getByRole('dialog').textContent).toContain(TAG_TEXT.open)
    fireEvent.pointerLeave(pill('어디 살든 가입'), { pointerType: 'mouse' })
    await vi.waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
  })

  it('tags=false draws no pills (book page, picks); a hand-added library keeps its old mark', () => {
    renderTags({ ...openLibrary, visitRequired: true }, false)
    expect(screen.queryByRole('button', { name: '어디 살든 가입' })).toBeNull()
    expect(screen.queryByRole('button', { name: '방문 가입' })).toBeNull()
    cleanup()
    renderTags({ ...mine, chosen: true }, false)
    expect(screen.queryByRole('button', { name: '직접 추가' })).toBeNull()
    expect(screen.getByText('직접 추가')).toBeTruthy()
  })
})
