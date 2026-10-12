import type { ResultRow } from '../search/sortBatch'

/** [확정] (R6-3): at most three library lines in the window, on every width; no "외 N곳". */
export const PICKS_HOLDING_LIMIT = 3

/**
 * The window's library lines (R6-3, R6-5) from the rows of the first three plan holdings: borrowable now (subscription
 * "바로 대출" included) first, then on loan with the fewest reservations first, then those that could not be decided,
 * then failed lookups last. Ties keep the plan order (직접 추가 → 가입 단계 …).
 */
export function pickLibraryRows(
  rows: readonly ResultRow[],
  planIndex: ReadonlyMap<number, number>,
  max: number = PICKS_HOLDING_LIMIT,
): ResultRow[] {
  const order = (row: ResultRow) => planIndex.get(row.libraryId) ?? Number.MAX_SAFE_INTEGER
  return [...rows]
    .sort(
      (a, b) =>
        group(a) - group(b) ||
        (group(a) === 1 ? compareNullableAsc(a.reservations, b.reservations) : 0) ||
        order(a) - order(b),
    )
    .slice(0, max)
}

function group(row: ResultRow): number {
  if (row.failed) return 3
  if (row.availability === 'AVAILABLE') return 0
  if (row.availability === 'RESERVABLE') return 1
  return 2
}

function compareNullableAsc(a: number | null, b: number | null): number {
  if (a === b) return 0
  if (a === null) return 1
  if (b === null) return -1
  return a - b
}
