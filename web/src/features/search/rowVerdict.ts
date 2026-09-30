import type { LibraryPage, RowVerdictEvent } from '../../api/types'
import type { ResultRow } from './sortBatch'

/** One batch of results: a round of the search, or one library's next page ("more"). */
export interface Batch {
  batchNo: number
  kind: 'round' | 'library'
  status: 'loading' | 'done'
  /** Libraries asked in this batch (for "도서관 N곳"). */
  requestedLibraries: number[]
  /** Rows in arrival order. The view sorts them within this batch only. */
  rows: ResultRow[]
}

/** Rows of one page, tagged with their library and page. */
export function rowsOf(page: LibraryPage): ResultRow[] {
  return page.items.map((item) => ({ ...item, libraryId: page.libraryId, page: page.page }))
}

/**
 * Applies a row verdict to its own batch only: the row (libraryId + page + rank) gets its final availability, which
 * moves it out of the "대출 정보 조회 중" place into its section when the batch is sorted. Other batches never
 * change (AC-24). The screen does not look at library or screen kinds here (ADR-0019).
 */
export function applyRowVerdict(batches: Batch[], verdict: RowVerdictEvent): Batch[] {
  return batches.map((batch) => {
    if (batch.batchNo !== verdict.batchNo) return batch
    let changed = false
    const rows = batch.rows.map((row) => {
      if (row.libraryId !== verdict.libraryId || row.page !== verdict.page || row.rank !== verdict.rank) return row
      changed = true
      return { ...row, availability: verdict.availability, availabilityBasis: verdict.availabilityBasis }
    })
    return changed ? { ...batch, rows } : batch
  })
}
