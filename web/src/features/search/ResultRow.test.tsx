import { cleanup, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeAll, describe, expect, it } from 'vitest'
import type { TargetLibrary } from '../../api/types'
import { ResultRow } from './ResultRow'
import { testRow } from './testRows'

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
}

/** The join details open from "ⓘ 가입 정보" on every width (user request 2026-10-05). */
async function renderOpen(library: TargetLibrary) {
  render(<ResultRow row={testRow({ libraryId: 2, rank: 1 })} library={library} searchUrl={undefined} />)
  await userEvent.setup().click(screen.getByRole('button', { name: `${library.name} 가입 정보` }))
  return screen.getByRole('dialog')
}

describe('join details behind "가입 정보"', () => {
  it('are not drawn until the button is pressed', () => {
    render(<ResultRow row={testRow({ libraryId: 2, rank: 1 })} library={openLibrary} searchUrl={undefined} />)

    expect(screen.queryByText('누구나 가입')).toBeNull()
    expect(screen.queryByText(/도서관 홈페이지 가입 후 로그인/)).toBeNull()
    expect(screen.getByRole('button', { name: `${openLibrary.name} 가입 정보` })).toBeTruthy()
  })
})

describe('ResultRow join caution (C2-U-11)', () => {
  it('shows the caution under "누구나 가입" without the "가입 자격 확인 필요" badge', async () => {
    const popover = await renderOpen({ ...openLibrary, joinCaution: '회원증 발급은 고흥군 거주자·직장인만' })

    expect(popover.textContent).toContain('누구나 가입')
    expect(popover.textContent).toContain('회원증 발급은 고흥군 거주자·직장인만')
    expect(screen.queryByText('가입 자격 확인 필요')).toBeNull()
  })

  it('shows a notice as "안내" without the caution mark', async () => {
    const popover = await renderOpen({ ...openLibrary, joinNotice: '시각장애인 이용 도서관' })

    expect(popover.textContent).toContain('안내')
    expect(popover.textContent).toContain('시각장애인 이용 도서관')
    expect(popover.textContent).not.toContain('주의')
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

  it("the same library without the user's region still needs a check (AC-12)", async () => {
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

    expect(screen.getByRole('dialog').textContent).toContain('가입 자격 확인 필요')
    expect(screen.getByRole('dialog').textContent).toContain('서산시 거주·재직·재학')
  })

  it('shows the "방문 가입" tag for a visit-only library', async () => {
    const popover = await renderOpen({ ...openLibrary, visitRequired: true })

    expect(popover.textContent).toContain('방문 가입')
  })

  it('shows no caution line when there is none', async () => {
    const popover = await renderOpen(openLibrary)

    expect(popover.textContent).not.toContain('주의')
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

  it('shows its eligibility, extra condition, caution and how to join from the same button (user request 2026-10-05)', async () => {
    const popover = await renderOpen(chosenLibrary)

    expect(popover.textContent).toContain('부산 거주')
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

