import type { LookupLevel, LookupReason } from '../../api/lookupStatus'

/**
 * What the header light shows (cycle 20 handoff 1-12, design-delta "웹 (화면)"): a level, where it came from and, for
 * a server answer, its reason. `stale`: no success for 30 seconds (spec R5); `busy`: a search was refused with 503
 * `server_busy`, red until the next status answer (spec R2).
 */
export type LookupView =
  | { level: LookupLevel; source: 'server'; reason: LookupReason }
  | { level: 'GRAY'; source: 'stale' }
  | { level: 'RED'; source: 'busy' }

/** The short text next to the dot [확정]. */
export const LEVEL_LABEL: Record<LookupLevel, string> = {
  GREEN: '조회 원활',
  YELLOW: '조회 붐빔',
  RED: '조회 혼잡',
  GRAY: '상태 모름',
}

/** The dot button's name: "조회 상태: 원활" [확정 for green; the other words follow the label]. */
export const LEVEL_ARIA: Record<LookupLevel, string> = {
  GREEN: '조회 상태: 원활',
  YELLOW: '조회 상태: 붐빔',
  RED: '조회 상태: 혼잡',
  GRAY: '조회 상태: 모름',
}

/** The second line of the popover, by color and reason [확정] (reviews #6, #21, #24, 마무리 확인 #7). */
export function secondLine(view: LookupView): string {
  if (view.source === 'stale') return '조회 상태를 받아오지 못했어요'
  if (view.source === 'busy') return '지금 찾는 사람이 많아 검색이 거절될 수 있어요'
  switch (view.level) {
    case 'GREEN':
      return '지금 바로 찾을 수 있어요'
    case 'YELLOW':
      return view.reason === 'VENDOR_PAUSED' ? '지금은 조회가 평소보다 느려요' : '찾는 사람이 늘고 있어요. 조금 느릴 수 있어요'
    case 'RED':
      return view.reason === 'NO_LANE' ? '지금은 도서관 조회를 할 수 없어요' : '지금 찾는 사람이 많아 검색이 거절될 수 있어요'
    case 'GRAY':
      // the server itself could not tell (DB error, 전반 점검 #9); a GRAY with another reason reads the same
      return '상태를 확인할 수 없어요'
  }
}

/** The label 「지금 이 도서관 조회가 밀려요」 on the rows of a queued library (spec R4, handoff 1-13) [확정]. */
export const QUEUED_LABEL = '지금 이 도서관 조회가 밀려요'
