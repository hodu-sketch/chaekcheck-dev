import { addUsageValues, conditionParam, readProblem, type UsageSearchValues } from './client'
import { SseParser } from './sseParser'
import type {
  HoldingStreamEvent,
  LibraryScope,
  ResultFilter,
  SearchField,
  StreamEvent,
  VisitFilter,
} from './types'

export interface SearchStreamParams {
  field: SearchField
  query: string
  filter: ResultFilter
  scope: LibraryScope
  visit: VisitFilter
  residenceRegionIds: number[]
  workRegionIds: number[]
  /** Libraries added by hand (cycle 06); sent only when not empty. */
  libraryIds: number[]
}

export interface BatchStreamParams {
  field: SearchField
  query: string
  filter: ResultFilter
  batchNo: number
  /** In request order; at most K (server limit). */
  candidates: Array<{ libraryId: number; page: number }>
  /** The search this batch belongs to, sent only for the usage log (cycle 15); the server does not search with it. */
  usage?: UsageSearchValues
}

const EVENT_NAMES = new Set([
  'targets',
  'library-requested',
  'library-result',
  'library-failed',
  'batch-done',
  'row-verdict',
  'done',
])

/** First batch: the plan (`targets`) and batch 1. */
export function searchStream(
  params: SearchStreamParams,
  onEvent: (event: StreamEvent) => void,
  signal: AbortSignal,
): Promise<void> {
  const search = new URLSearchParams({
    ...conditionParam(params.field, params.query),
    filter: params.filter,
    scope: params.scope,
    visit: params.visit,
  })
  if (params.libraryIds.length) search.set('libraryIds', params.libraryIds.join(','))
  if (params.residenceRegionIds.length) search.set('residenceRegionIds', params.residenceRegionIds.join(','))
  if (params.workRegionIds.length) search.set('workRegionIds', params.workRegionIds.join(','))
  return readStream(`/api/v1/ebook-searches/stream?${search}`, EVENT_NAMES, onEvent, signal)
}

/** Next batch: the browser sends the candidates; the server keeps no search state (ADR-0014). */
export function batchStream(
  params: BatchStreamParams,
  onEvent: (event: StreamEvent) => void,
  signal: AbortSignal,
): Promise<void> {
  const search = new URLSearchParams({
    ...conditionParam(params.field, params.query),
    filter: params.filter,
    batchNo: String(params.batchNo),
    candidates: params.candidates.map((c) => `${c.libraryId}:${c.page}`).join(','),
  })
  addUsageValues(search, params.usage)
  return readStream(`/api/v1/ebook-searches/batches/stream?${search}`, EVENT_NAMES, onEvent, signal)
}

/**
 * Opens a stream with `fetch`, not `EventSource`: so a 400/422 body can be read as Problem Details, and so there is
 * no automatic reconnect that would repeat library requests. Resolves when the server closes the stream after
 * `done`. Rejects with ApiError before any event when the request is invalid, and with an AbortError when `signal`
 * aborts.
 */
async function readStream<E extends { name: string }>(
  url: string,
  names: ReadonlySet<string>,
  onEvent: (event: E) => void,
  signal: AbortSignal,
): Promise<void> {
  const response = await fetch(url, {
    headers: { Accept: 'text/event-stream, application/problem+json' },
    signal,
  })
  if (!response.ok) throw await readProblem(response)
  if (!response.body) throw new Error('Stream has no body')

  const reader = response.body.pipeThrough(new TextDecoderStream()).getReader()
  const parser = new SseParser()
  for (;;) {
    const { value, done } = await reader.read()
    if (done) return
    for (const raw of parser.push(value)) {
      if (!names.has(raw.event)) continue
      const event = { name: raw.event, data: JSON.parse(raw.data) } as unknown as E
      onEvent(event)
      if (event.name === 'done') {
        await reader.cancel()
        return
      }
    }
  }
}

// ---- cycle 04 holding streams (ADR-0030) ----

const HOLDING_EVENT_NAMES = new Set(['targets', 'holding-requested', 'holding-result', 'holding-failed', 'batch-done', 'done'])

export interface HoldingStreamParams {
  /** In result order; at most 20 (server limit, same as the book page size). */
  bookIds: number[]
  filter: ResultFilter
  scope: LibraryScope
  visit: VisitFilter
  residenceRegionIds: number[]
  workRegionIds: number[]
  /** Libraries added by hand (cycle 06); sent only when not empty. */
  libraryIds: number[]
  /** Cycle 10 (S14): only the first N plan holdings, in plan order (1..20); left out = all, as before. */
  holdingLimit?: number
}

export interface HoldingBatchParams {
  filter: ResultFilter
  batchNo: number
  /** Holding ids in request order; at most K. */
  candidates: number[]
}

/** First holding batch: the plan libraries holding the books (`targets`) and batch 1 over their detail pages. */
export function holdingStream(
  params: HoldingStreamParams,
  onEvent: (event: HoldingStreamEvent) => void,
  signal: AbortSignal,
): Promise<void> {
  const search = new URLSearchParams({
    bookIds: params.bookIds.join(','),
    filter: params.filter,
    scope: params.scope,
    visit: params.visit,
  })
  if (params.libraryIds.length) search.set('libraryIds', params.libraryIds.join(','))
  if (params.residenceRegionIds.length) search.set('residenceRegionIds', params.residenceRegionIds.join(','))
  if (params.workRegionIds.length) search.set('workRegionIds', params.workRegionIds.join(','))
  if (params.holdingLimit !== undefined) search.set('holdingLimit', String(params.holdingLimit))
  return readStream(`/api/v1/book-availabilities/stream?${search}`, HOLDING_EVENT_NAMES, onEvent, signal)
}

/** Next holding batch: the browser sends the holdings still to ask; the server keeps no state (ADR-0014). */
export function holdingBatchStream(
  params: HoldingBatchParams,
  onEvent: (event: HoldingStreamEvent) => void,
  signal: AbortSignal,
): Promise<void> {
  const search = new URLSearchParams({
    filter: params.filter,
    batchNo: String(params.batchNo),
    candidates: params.candidates.join(','),
  })
  return readStream(`/api/v1/book-availabilities/batches/stream?${search}`, HOLDING_EVENT_NAMES, onEvent, signal)
}
