import type { TargetLibrary } from '../../api/types'
import { libraryGroup } from './resultGroups'

/** The texts of the join guide window (user 2026-10-19, approved pictures join-pc-a / join-phone-b) [확정]. */
export const JOIN_GUIDE_TEXT = {
  eyebrow: '가입 방법',
  title: (name: string) => `${name} 가입 안내`,
  lead: '처음 이용한다면 아래 순서대로 준비해 주세요.',
  target: '가입 대상',
  way: '가입 방법',
  prepare: '준비할 것',
  chosen: '직접 추가한 도서관',
  mine: '내 지역에서 가입',
  open: '어디 살든 가입',
  other: '가입 조건 확인 필요',
  visit: '방문 가입',
  visitOutside: '지역 외는 방문 가입',
  online: '온라인 가입',
  onlineThenVisit: '온라인 가입 후 도서관 방문',
  noMethod: '도서관 홈페이지의 회원가입 안내를 확인해 주세요',
  changes: '가입 조건은 바뀔 수 있어요. 가입 전 공식 안내에서 최신 조건을 확인해 주세요.',
  member: '이미 회원이에요 · 책 보기',
  footer: ['가입과 대출은', '해당 도서관에서 진행해요.'],
  official: '공식 가입 안내 보기',
} as const

/** "가입 대상": our own category of the library, its eligibility summary where the category is a region. */
export function joinTarget(library: TargetLibrary): string {
  if (library.chosen) return JOIN_GUIDE_TEXT.chosen
  switch (libraryGroup(library)) {
    case 'MINE':
      return library.eligibilitySummary ?? JOIN_GUIDE_TEXT.mine
    case 'OPEN':
      return JOIN_GUIDE_TEXT.open
    default:
      return library.eligibilitySummary ?? JOIN_GUIDE_TEXT.other
  }
}

/**
 * The visit label of a library for this search, null without a visit: 「지역 외는 방문 가입」 when the library lets its
 * region's residents join online and this user is outside it (user 2026-10-20), else 「방문 가입」. A user of the region
 * gets `visitRequired` false from the server, so no label.
 */
export function visitLabel(library: TargetLibrary): string | null {
  if (!library.visitRequired) return null
  return library.visitOutsideRegion ? JOIN_GUIDE_TEXT.visitOutside : JOIN_GUIDE_TEXT.visit
}

/** "가입 방법": online only, or online and then one visit. */
export function joinWay(library: TargetLibrary): string {
  return library.visitRequired ? JOIN_GUIDE_TEXT.onlineThenVisit : JOIN_GUIDE_TEXT.online
}

/** "준비할 것" from the join method's wording; null when it names nothing (the row is left out). */
export function joinPrepare(joinMethod: string | null): string | null {
  if (!joinMethod) return null
  const id = joinMethod.includes('신분증')
  const verify = ['본인인증', '본인 인증', '휴대폰', '아이핀'].some((word) => joinMethod.includes(word))
  if (id && verify) return '본인인증 수단 · 신분증'
  if (verify) return '본인인증 수단'
  if (id) return '신분증'
  return null
}

/** The join method split at "→": 2+ parts are numbered steps, one part is a sentence, null is the fallback text. */
export type JoinSteps = { kind: 'steps'; steps: string[] } | { kind: 'text'; text: string }

export function joinSteps(joinMethod: string | null): JoinSteps {
  const parts = (joinMethod ?? '')
    .split('→')
    .map((part) => part.trim())
    .filter((part) => part.length > 0)
  if (parts.length >= 2) return { kind: 'steps', steps: parts }
  if (parts.length === 1) return { kind: 'text', text: parts[0] }
  return { kind: 'text', text: JOIN_GUIDE_TEXT.noMethod }
}
