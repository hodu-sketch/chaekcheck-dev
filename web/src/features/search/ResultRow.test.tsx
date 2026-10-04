import { cleanup, render, screen } from '@testing-library/react'
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

describe('ResultRow join caution (C2-U-11)', () => {
  it('shows the caution under "누구나 가입" without the "가입 자격 확인 필요" badge', () => {
    const library = { ...openLibrary, joinCaution: '회원증 발급은 고흥군 거주자·직장인만' }
    render(<ResultRow row={testRow({ libraryId: 2, rank: 1 })} library={library} searchUrl={undefined} />)

    expect(screen.getAllByText('누구나 가입').length).toBeGreaterThan(0)
    expect(screen.getAllByText(/회원증 발급은 고흥군 거주자·직장인만/).length).toBeGreaterThan(0)
    expect(screen.queryByText('가입 자격 확인 필요')).toBeNull()
  })

  it('shows a notice as "안내" without the caution mark', () => {
    const library = { ...openLibrary, joinNotice: '시각장애인 이용 도서관' }
    render(<ResultRow row={testRow({ libraryId: 2, rank: 1 })} library={library} searchUrl={undefined} />)

    expect(screen.getAllByText('안내').length).toBeGreaterThan(0)
    expect(screen.getAllByText(/시각장애인 이용 도서관/).length).toBeGreaterThan(0)
    expect(screen.queryByText('주의')).toBeNull()
  })

  it('a matching region with a 책이음 text is joinable; the text is only shown (AC-12)', () => {
    const library: TargetLibrary = {
      ...openLibrary,
      priorityGroup: 'MY_SIGUNGU',
      eligibility: 'ELIGIBLE',
      eligibilitySummary: '서산시 거주·재직·재학',
      extraConditionText: '공공도서관 책이음서비스 회원인 사람도 가입 대상',
    }
    render(<ResultRow row={testRow({ libraryId: 2, rank: 1 })} library={library} searchUrl={undefined} />)

    expect(screen.getAllByText(/책이음서비스 회원인 사람도/).length).toBeGreaterThan(0)
    expect(screen.queryByText('가입 자격 확인 필요')).toBeNull()
  })

  it('the same library without the user\'s region still needs a check (AC-12)', () => {
    const library: TargetLibrary = {
      ...openLibrary,
      priorityGroup: 'NEEDS_CHECK',
      eligibility: 'UNVERIFIED',
      eligibilitySummary: '서산시 거주·재직·재학',
      extraConditionText: '공공도서관 책이음서비스 회원인 사람도 가입 대상',
    }
    render(<ResultRow row={testRow({ libraryId: 2, rank: 1 })} library={library} searchUrl={undefined} />)

    expect(screen.getAllByText('가입 자격 확인 필요').length).toBeGreaterThan(0)
  })

  it('shows the "방문 가입" tag for a visit-only library', () => {
    const library = { ...openLibrary, visitRequired: true }
    render(<ResultRow row={testRow({ libraryId: 2, rank: 1 })} library={library} searchUrl={undefined} />)

    expect(screen.getAllByText('방문 가입').length).toBeGreaterThan(0)
  })

  it('shows no caution line when there is none', () => {
    render(<ResultRow row={testRow({ libraryId: 2, rank: 1 })} library={openLibrary} searchUrl={undefined} />)

    expect(screen.queryByText('주의')).toBeNull()
  })
})

describe('a library added by hand (cycle 06, AC-5)', () => {
  it('shows only "직접 추가": no eligibility badge, summary, extra condition or caution', () => {
    const library: TargetLibrary = {
      ...openLibrary,
      priorityGroup: 'QUALIFIED',
      eligibility: 'UNKNOWN',
      eligibilitySummary: '부산 거주',
      extraConditionText: '책이음 회원 가입 가능',
      joinCaution: '회원증 발급은 부산 시민만',
      chosen: true,
    }
    render(<ResultRow row={testRow({ libraryId: 2, rank: 1 })} library={library} searchUrl={undefined} />)

    expect(screen.getByText('직접 추가')).toBeTruthy()
    expect(screen.queryByText('가입 자격 확인 필요')).toBeNull()
    expect(screen.queryByText('부산 거주')).toBeNull()
    expect(screen.queryByText('책이음 회원 가입 가능')).toBeNull()
    expect(screen.queryByText(/회원증 발급은 부산 시민만/)).toBeNull()
    // how to join stays
    expect(screen.getAllByText(/도서관 홈페이지 가입 후 로그인/).length).toBeGreaterThan(0)
  })

  it('keeps the "방문 가입" tag', () => {
    render(
      <ResultRow
        row={testRow({ libraryId: 2, rank: 1 })}
        library={{ ...openLibrary, visitRequired: true, chosen: true }}
        searchUrl={undefined}
      />,
    )

    expect(screen.getAllByText('방문 가입').length).toBeGreaterThan(0)
    expect(screen.queryByText('누구나 가입')).toBeNull()
  })
})
