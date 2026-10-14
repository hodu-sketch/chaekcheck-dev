import type { BookItem, HoldingStreamEvent, LibraryItem, TargetLibrary } from '../../api/types'
import type { Blocked } from './blocked'
import type { Batch } from './rowVerdict'
import type { ResultRow } from './sortBatch'

/**
 * Where each holding stands in this search (ADR-0030): the plan state is keyed by holding, not by library, because a
 * detail page has no pages. The browser keeps it; the server keeps no state (ADR-0014).
 */
export type HoldingStatus = 'NotRequested' | 'Requested' | 'Done' | 'Failed'

export interface HoldingEntry {
  status: HoldingStatus
  bookId: number
  libraryId: number
}

export interface HoldingsState {
  phase: 'idle' | 'streaming' | 'done'
  /** Plan libraries that hold a result book, in plan order; later streams add new ones at the end. */
  libraries: TargetLibrary[]
  regionFilterApplied: boolean
  /** Holding ids in request order (book order, then plan order inside a book). */
  order: number[]
  entries: Record<number, HoldingEntry>
  batches: Batch[]
  /** The last stream could not be opened or broke off. */
  batchFailed: boolean
  /** The last stream was refused with 429 or 503 (cycle 08 R2); cleared by the next batch. */
  blocked: Blocked | null
}

export const EMPTY_HOLDINGS: HoldingsState = {
  phase: 'idle',
  libraries: [],
  regionFilterApplied: false,
  order: [],
  entries: {},
  batches: [],
  batchFailed: false,
  blocked: null,
}

export type HoldingsAction =
  | { type: 'reset' }
  | { type: 'batch-start'; batchNo: number }
  | { type: 'event'; batchNo: number; event: HoldingStreamEvent; books: ReadonlyMap<number, BookItem> }
  | { type: 'batch-failed'; batchNo: number; blocked?: Blocked | null }

export function holdingsReducer(state: HoldingsState, action: HoldingsAction): HoldingsState {
  switch (action.type) {
    case 'reset':
      return EMPTY_HOLDINGS
    case 'batch-start':
      return {
        ...state,
        phase: 'streaming',
        batchFailed: false,
        blocked: null,
        batches: [...state.batches, { batchNo: action.batchNo, kind: 'round', status: 'loading', requestedLibraries: [], rows: [] }],
      }
    case 'batch-failed':
      return {
        ...updateBatch(state, action.batchNo, (b) => ({ ...b, status: 'done' })),
        phase: 'done',
        batchFailed: true,
        blocked: action.blocked ?? null,
      }
    case 'event':
      return onEvent(state, action.batchNo, action.event, action.books)
  }
}

function onEvent(
  state: HoldingsState,
  batchNo: number,
  event: HoldingStreamEvent,
  books: ReadonlyMap<number, BookItem>,
): HoldingsState {
  switch (event.name) {
    case 'targets': {
      const known = new Set(state.libraries.map((l) => l.id))
      const entries = { ...state.entries }
      const order = [...state.order]
      for (const holding of event.data.holdings) {
        if (entries[holding.holdingId]) continue
        entries[holding.holdingId] = { status: 'NotRequested', bookId: holding.bookId, libraryId: holding.libraryId }
        order.push(holding.holdingId)
      }
      return {
        ...state,
        libraries: [...state.libraries, ...event.data.libraries.filter((l) => !known.has(l.id))],
        regionFilterApplied: event.data.regionFilterApplied,
        order,
        entries,
      }
    }
    case 'holding-requested': {
      const next = mark(state, event.data.holdingId, 'Requested')
      return updateBatch(next, batchNo, (b) => ({ ...b, requestedLibraries: [...b.requestedLibraries, event.data.libraryId] }))
    }
    case 'holding-result': {
      const { holdingId, bookId, libraryId, item } = event.data
      const next = mark(state, holdingId, 'Done')
      return updateBatch(next, batchNo, (b) => ({ ...b, rows: [...b.rows, holdingRow(item, holdingId, bookId, libraryId)] }))
    }
    case 'holding-failed': {
      const { holdingId, problem } = event.data
      const entry = state.entries[holdingId]
      const next = mark(state, holdingId, 'Failed')
      if (!entry) return next
      // that row alone shows "확인 실패" (cycle 02 rule; also a detail page that no longer exists), or "빠졌어요" when
      // the holding left the catalog at an import since the page was opened (ADR-0070)
      const gone = problem?.code === 'holding_not_found'
      return updateBatch(next, batchNo, (b) => ({
        ...b,
        rows: [...b.rows, failedRow(holdingId, entry.bookId, entry.libraryId, books.get(entry.bookId), gone)],
      }))
    }
    case 'batch-done':
      return state
    case 'done':
      return { ...updateBatch(state, batchNo, (b) => ({ ...b, status: 'done' })), phase: 'done' }
  }
}

/** One row = one book at one library; `page` carries the book and `rank` the holding so keys stay unique. */
export function holdingRow(item: LibraryItem, holdingId: number, bookId: number, libraryId: number): ResultRow {
  return { ...item, libraryId, page: bookId, rank: holdingId, bookId }
}

function failedRow(
  holdingId: number,
  bookId: number,
  libraryId: number,
  book: BookItem | undefined,
  gone: boolean,
): ResultRow {
  return {
    libraryId,
    page: bookId,
    rank: holdingId,
    bookId,
    title: book?.title ?? '',
    author: book?.authorDisplay ?? null,
    publisher: book?.publisher ?? null,
    supplier: null,
    holdings: null,
    loaned: null,
    reservations: null,
    available: null,
    titleKey: `book:${bookId}`,
    availability: 'UNKNOWN',
    availabilityBasis: 'COUNT',
    detailUrl: null,
    failed: true,
    gone,
  }
}

/** Holdings still to ask, in order, at most `max` (K). */
export function nextHoldingCandidates(state: HoldingsState, max: number): number[] {
  return state.order.filter((id) => state.entries[id]?.status === 'NotRequested').slice(0, max)
}

export function remainingHoldings(state: HoldingsState): number {
  return state.order.filter((id) => state.entries[id]?.status === 'NotRequested').length
}

export function nextBatchNo(state: HoldingsState): number {
  return state.batches.reduce((max, b) => Math.max(max, b.batchNo), 0) + 1
}

function mark(state: HoldingsState, holdingId: number, status: HoldingStatus): HoldingsState {
  const entry = state.entries[holdingId]
  return entry ? { ...state, entries: { ...state.entries, [holdingId]: { ...entry, status } } } : state
}

function updateBatch(state: HoldingsState, batchNo: number, change: (batch: Batch) => Batch): HoldingsState {
  return { ...state, batches: state.batches.map((b) => (b.batchNo === batchNo ? change(b) : b)) }
}
