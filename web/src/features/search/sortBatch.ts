import type { Eligibility, LibraryItem, ResultFilter } from '../../api/types'

/** One result line: one book at one library and page (same books are not merged). */
export interface ResultRow extends LibraryItem {
  libraryId: number
  page: number
  /** The lookup of this row failed (holding stream): shown as "확인 실패". */
  failed?: boolean
  /** The holding left the library's list at the last import (`holding_not_found`, ADR-0070): its own text. */
  gone?: boolean
  /** The book of a holding row (catalog search, book page, picks); none on a direct search (cycle 15 usage log). */
  bookId?: number
}

export interface SortContext {
  /** Position of each library in the plan (`targets` order); the "library rank" tie-breaker. */
  planIndex: ReadonlyMap<number, number>
  eligibility: ReadonlyMap<number, Eligibility>
  /** Libraries with a join caution (C2-U-11); among joinable libraries they come after those without one. */
  cautioned: ReadonlySet<number>
  /** Libraries one can only join with a visit; among joinable libraries they come last (user decision 2026-09-29). */
  visitRequired: ReadonlySet<number>
  /** Library loan rate from its page 1 only; later pages never change it (spec 5.4). */
  loanRates: ReadonlyMap<number, number | null>
  /** Libraries the user added by hand (cycle 06, ADR-0050): first inside a title, before every join tier. */
  chosen: ReadonlySet<number>
}

export interface SortedBatch {
  /** 지금 빌릴 수 있어요: AVAILABLE */
  available: ResultRow[]
  /** 예약하고 기다려요: RESERVABLE, then UNKNOWN at the bottom */
  reserve: ResultRow[]
  /** 대출 정보 조회 중: PENDING, until their row-verdict arrives */
  pending: ResultRow[]
}

/**
 * "가입 자격 확인 필요": region rules without the user's regions (UNKNOWN), no verified rules (UNVERIFIED), or join
 * rules not researched (UNSURVEYED, cycle 07).
 */
export function needsJoinCheck(eligibility: Eligibility | undefined): boolean {
  return eligibility === 'UNKNOWN' || eligibility === 'UNVERIFIED' || eligibility === 'UNSURVEYED'
}

/**
 * Sorts the rows of one batch (spec 5.6). Only this batch's rows are compared; earlier batches keep their order.
 * 1. Sections by availability.
 * 2. Inside a section, rows with the same `titleKey` form a title group.
 *    - borrow: more stock first; titles with only count-less rows (portal, subscription) after them [안]
 *    - reserve: fewer reservations first
 *    - ties: the title whose earliest library comes first in the plan [안]
 * 3. Inside a title: libraries the user added by hand (cycle 06, ADR-0050), then joinable libraries (OPEN, ELIGIBLE)
 *    without a join caution, then joinable ones with a caution (user request 2026-09-29), then joinable ones that need
 *    a visit (user decision 2026-09-29), then "가입 자격 확인 필요"; then
 *    - borrow: stock desc -> loan rate asc (unknown last) -> rank; count-less rows after, in plan order -> rank [안]
 *    - reserve: reservations asc -> holdings desc (unknown last) -> rank
 */
export function sortBatch(rows: ResultRow[], context: SortContext): SortedBatch {
  const planIndex = (row: ResultRow) => context.planIndex.get(row.libraryId) ?? Number.MAX_SAFE_INTEGER
  const joinTier = (row: ResultRow) => {
    if (context.chosen.has(row.libraryId)) return -1
    if (needsJoinCheck(context.eligibility.get(row.libraryId))) return 3
    if (context.visitRequired.has(row.libraryId)) return 2
    return context.cautioned.has(row.libraryId) ? 1 : 0
  }
  const loanRate = (row: ResultRow) => context.loanRates.get(row.libraryId) ?? null

  const borrowInTitle = (a: ResultRow, b: ResultRow) =>
    joinTier(a) - joinTier(b) ||
    Number(a.available === null) - Number(b.available === null) ||
    (a.available !== null && b.available !== null
      ? b.available - a.available || compareNullableAsc(loanRate(a), loanRate(b)) || a.rank - b.rank
      : planIndex(a) - planIndex(b) || a.rank - b.rank) ||
    planIndex(a) - planIndex(b)
  const reserveInTitle = (a: ResultRow, b: ResultRow) =>
    joinTier(a) - joinTier(b) ||
    compareNullableAsc(a.reservations, b.reservations) ||
    compareNullableAsc(neg(a.holdings), neg(b.holdings)) ||
    a.rank - b.rank ||
    planIndex(a) - planIndex(b)

  const available = rows.filter((row) => row.availability === 'AVAILABLE')
  const reservable = rows.filter((row) => row.availability === 'RESERVABLE')
  const unknown = rows.filter((row) => row.availability === 'UNKNOWN')
  const pending = rows
    .filter((row) => row.availability === 'PENDING')
    .sort((a, b) => planIndex(a) - planIndex(b) || a.rank - b.rank)

  const borrowGroups = groupByTitle(available, borrowInTitle).sort((a, b) => {
    const stockA = stock(a.rows)
    const stockB = stock(b.rows)
    return (
      Number(stockA === null) - Number(stockB === null) ||
      (stockB ?? 0) - (stockA ?? 0) ||
      firstPlanIndex(a.rows, planIndex) - firstPlanIndex(b.rows, planIndex)
    )
  })
  const reserveGroups = groupByTitle(reservable, reserveInTitle).sort(
    (a, b) =>
      sum(a.rows, (row) => row.reservations) - sum(b.rows, (row) => row.reservations) ||
      firstPlanIndex(a.rows, planIndex) - firstPlanIndex(b.rows, planIndex),
  )
  const unknownGroups = groupByTitle(unknown, reserveInTitle).sort(
    (a, b) => firstPlanIndex(a.rows, planIndex) - firstPlanIndex(b.rows, planIndex),
  )

  return {
    available: borrowGroups.flatMap((group) => group.rows),
    reserve: [...reserveGroups, ...unknownGroups].flatMap((group) => group.rows),
    pending,
  }
}

/** "대출만" keeps rows that can be borrowed now, and the rows still being checked (they may become borrowable). */
export function applyFilter(sorted: SortedBatch, filter: ResultFilter): SortedBatch {
  return filter === 'available' ? { ...sorted, reserve: [] } : sorted
}

interface TitleGroup {
  key: string
  rows: ResultRow[]
}

function groupByTitle(rows: ResultRow[], inTitle: (a: ResultRow, b: ResultRow) => number): TitleGroup[] {
  const groups = new Map<string, ResultRow[]>()
  for (const row of rows) {
    const key = row.titleKey || row.title
    const group = groups.get(key)
    if (group) group.push(row)
    else groups.set(key, [row])
  }
  return [...groups.entries()].map(([key, groupRows]) => ({ key, rows: groupRows.sort(inTitle) }))
}

/** Total stock of a title; null when no row of it shows counts. */
function stock(rows: ResultRow[]): number | null {
  const counted = rows.filter((row) => row.available !== null)
  return counted.length === 0 ? null : counted.reduce((total, row) => total + (row.available ?? 0), 0)
}

function sum(rows: ResultRow[], value: (row: ResultRow) => number | null): number {
  return rows.reduce((total, row) => total + (value(row) ?? 0), 0)
}

function firstPlanIndex(rows: ResultRow[], planIndex: (row: ResultRow) => number): number {
  return Math.min(...rows.map(planIndex))
}

function neg(value: number | null): number | null {
  return value === null ? null : -value
}

function compareNullableAsc(a: number | null, b: number | null): number {
  if (a === b) return 0
  if (a === null) return 1
  if (b === null) return -1
  return a - b
}
