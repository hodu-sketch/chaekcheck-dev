import { describe, expect, it } from 'vitest'
import type { TargetLibrary } from '../../api/types'
import { JOIN_GUIDE_TEXT, joinPrepare, joinSteps, joinTarget, joinWay } from './joinGuideParts'

const base: TargetLibrary = {
  id: 1,
  name: '가 도서관',
  libraryType: 'EBOOK',
  platformType: 'KYOBO_V1',
  priorityGroup: 'OPEN',
  deferred: false,
  extraConditionText: null,
  visitRequired: false,
  eligibility: 'OPEN',
  eligibilitySummary: null,
  joinMethod: null,
  joinUrl: null,
  joinCaution: null,
  joinNotice: null,
  homepageUrl: null,
  chosen: false,
  joinSurvey: 'SURVEYED',
  logoUrl: null,
  mobileSiteDiffers: false,
}

/** The join guide window's parts (user 2026-10-19). */
describe('join steps from the join method', () => {
  it('2+ parts split at "→" are numbered steps, trimmed, without empty parts', () => {
    expect(joinSteps(' 홈페이지 신규회원 가입(본인인증) → 로그인 후 [전자책도서관]에서 대출 →  ')).toEqual({
      kind: 'steps',
      steps: ['홈페이지 신규회원 가입(본인인증)', '로그인 후 [전자책도서관]에서 대출'],
    })
    expect(joinSteps('가→나→다')).toEqual({ kind: 'steps', steps: ['가', '나', '다'] })
  })

  it('one part is that sentence', () => {
    expect(joinSteps('  도서관 홈페이지 가입 후 로그인 ')).toEqual({ kind: 'text', text: '도서관 홈페이지 가입 후 로그인' })
    expect(joinSteps('→ 홈페이지 가입 →')).toEqual({ kind: 'text', text: '홈페이지 가입' })
  })

  it('none is the fallback text', () => {
    expect(joinSteps(null)).toEqual({ kind: 'text', text: JOIN_GUIDE_TEXT.noMethod })
    expect(joinSteps(' → ')).toEqual({ kind: 'text', text: '도서관 홈페이지의 회원가입 안내를 확인해 주세요' })
  })
})

describe('what to bring', () => {
  it.each([
    ['최초 1회 방문(신분증)해 대출증 발급', '신분증'],
    ['신규회원 가입(본인인증)', '본인인증 수단'],
    ['본인 인증 후 가입', '본인인증 수단'],
    ['휴대폰 인증 가입', '본인인증 수단'],
    ['아이핀으로 가입', '본인인증 수단'],
    ['통합회원 가입(휴대폰 인증) → 방문(신분증)', '본인인증 수단 · 신분증'],
  ])('%s → %s', (method, prepare) => {
    expect(joinPrepare(method)).toBe(prepare)
  })

  it('nothing named or no method: no row', () => {
    expect(joinPrepare('홈페이지 가입')).toBeNull()
    expect(joinPrepare(null)).toBeNull()
  })
})

describe('the target by our category', () => {
  it('a library added by hand', () => {
    expect(joinTarget({ ...base, chosen: true, priorityGroup: 'QUALIFIED', eligibilitySummary: '부산 거주' })).toBe(
      '직접 추가한 도서관',
    )
  })

  it.each([
    [{ priorityGroup: 'MY_SIGUNGU' }],
    [{ priorityGroup: 'MY_SIDO' }],
    [{ priorityGroup: 'QUALIFIED', eligibility: 'ELIGIBLE' }],
  ] as const)('my region (%o): its summary, else "내 지역에서 가입"', (overrides) => {
    const library = { ...base, eligibility: 'ELIGIBLE' as const, ...overrides }
    expect(joinTarget({ ...library, eligibilitySummary: '서산시 거주·재직·재학' })).toBe('서산시 거주·재직·재학')
    expect(joinTarget(library)).toBe('내 지역에서 가입')
  })

  it('joinable wherever one lives', () => {
    expect(joinTarget({ ...base, eligibilitySummary: '누구나' })).toBe('어디 살든 가입')
  })

  it('③ of a nationwide search: its summary, else "가입 조건 확인 필요"', () => {
    const other = { ...base, priorityGroup: 'NEEDS_CHECK' as const, eligibility: 'UNVERIFIED' as const }
    expect(joinTarget({ ...other, eligibilitySummary: '부산 거주·직장' })).toBe('부산 거주·직장')
    expect(joinTarget(other)).toBe('가입 조건 확인 필요')
  })
})

describe('how to join', () => {
  it('online, or online and a visit', () => {
    expect(joinWay(base)).toBe('온라인 가입')
    expect(joinWay({ ...base, visitRequired: true })).toBe('온라인 가입 후 도서관 방문')
  })
})
