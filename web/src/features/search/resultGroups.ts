import type { TargetLibrary } from '../../api/types'
import type { BatchView } from './ResultList'
import type { ResultRow } from './sortBatch'

/**
 * The result groups (cycle 22 R5-1, ADR-0136), in screen order: ① 내 도서관에 있어요 ② 어디 살든 가입되는 도서관에 있어요
 * ③ 그 밖의 도서관에 있어요 ④ 확인하지 못한 도서관 (cycle 20).
 */
export type ResultGroupKey = 'MINE' | 'OPEN' | 'OTHER' | 'UNCHECKED'

export const GROUP_ORDER: readonly ResultGroupKey[] = ['MINE', 'OPEN', 'OTHER', 'UNCHECKED']

export interface ResultGroup {
  key: ResultGroupKey
  /** 지금 빌릴 수 있어요: every batch's `sorted.available` of this group, batch after batch (R5-3). */
  available: ResultRow[]
  /** 예약하고 기다려요: every batch's `sorted.reserve` of this group, batch after batch. */
  reserve: ResultRow[]
  /** 대출 정보 조회 중: every batch's `sorted.pending` of this group, batch after batch. */
  pending: ResultRow[]
  /** Only in UNCHECKED: every batch's `sorted.unchecked`, batch after batch (the list may sort them in plan order). */
  unchecked: ResultRow[]
  /** Rows in this group, for "(n)" in its title; each subsection's "(n)" is its array's length. */
  count: number
}

export interface GroupedResults {
  /** Always four groups in `GROUP_ORDER`, empty ones included (the screen decides what to hide, R5-4). */
  groups: ResultGroup[]
  /**
   * N of "이 중 N곳은 가입하려면 도서관에 가야 해요" (R8-2): distinct sites (`libraryId`) with `visitRequired` among the
   * OPEN rows shown now (available, reserve and pending after the filter).
   */
  visitRequiredOpenSites: number
}

/**
 * The group of a library (D3'): hand-added, my sigungu or sido, or eligible by my regions → MINE; joinable wherever one
 * lives → OPEN; anything else (QUALIFIED, NEEDS_CHECK, UNKNOWN, UNVERIFIED, UNSURVEYED, or a library not in the plan)
 * → OTHER.
 */
export function libraryGroup(library: TargetLibrary | undefined): Exclude<ResultGroupKey, 'UNCHECKED'> {
  if (!library) return 'OTHER'
  if (library.chosen || library.priorityGroup === 'MY_SIGUNGU' || library.priorityGroup === 'MY_SIDO') return 'MINE'
  if (library.eligibility === 'ELIGIBLE') return 'MINE'
  if (library.eligibility === 'OPEN') return 'OPEN'
  return 'OTHER'
}

/**
 * Splits the sorted batches into the result groups (D3). Each batch keeps its `sortBatch` order; a later batch's rows go
 * to the end of their own subsection in their own group (R5-3), never moving rows between groups. Rows that could not
 * be checked (`sorted.unchecked`, cycle 20) all go to UNCHECKED whatever their library's group.
 */
export function groupBatches(
  views: readonly BatchView[],
  libraryById: ReadonlyMap<number, TargetLibrary>,
): GroupedResults {
  const groups = new Map<ResultGroupKey, ResultGroup>(
    GROUP_ORDER.map((key) => [key, { key, available: [], reserve: [], pending: [], unchecked: [], count: 0 }]),
  )
  const groupOf = (row: ResultRow) => groups.get(libraryGroup(libraryById.get(row.libraryId)))!

  for (const { sorted } of views) {
    for (const row of sorted.available) groupOf(row).available.push(row)
    for (const row of sorted.reserve) groupOf(row).reserve.push(row)
    for (const row of sorted.pending) groupOf(row).pending.push(row)
    groups.get('UNCHECKED')!.unchecked.push(...sorted.unchecked)
  }

  const ordered = GROUP_ORDER.map((key) => {
    const group = groups.get(key)!
    group.count = group.available.length + group.reserve.length + group.pending.length + group.unchecked.length
    return group
  })

  const open = groups.get('OPEN')!
  const visitSites = new Set(
    [...open.available, ...open.reserve, ...open.pending]
      .map((row) => row.libraryId)
      .filter((id) => libraryById.get(id)?.visitRequired),
  )

  return { groups: ordered, visitRequiredOpenSites: visitSites.size }
}
