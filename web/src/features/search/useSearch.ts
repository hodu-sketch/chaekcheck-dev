import { useCallback, useEffect, useReducer, useRef } from 'react'
import { ApiError, fetchLibraryPage } from '../../api/client'
import { batchStream, searchStream } from '../../api/searchStream'
import type {
  LibraryPage,
  LibraryScope,
  Problem,
  ResultFilter,
  SearchField,
  StreamEvent,
  TargetsEvent,
  VisitFilter,
} from '../../api/types'
import type { StoredRegions } from '../region/regionStorage'
import { applyRowVerdict, rowsOf, type Batch } from './rowVerdict'
import {
  initialPlan,
  markFailed,
  markPage,
  markRequested,
  nextCandidates,
  type PlanState,
} from './searchPlan'

/** Candidates per next-batch request (K). Same value as the server's `batch.max-candidates` [안]. */
export const MAX_CANDIDATES = 50

/** What the summary bar shows for one requested library. */
export interface LibraryResult {
  status: 'loading' | 'loaded' | 'failed'
  /** Pages in the order they arrived. */
  pages: LibraryPage[]
  /** From page 1; kept fixed when more pages arrive (spec 5.4). */
  loanRate: number | null
  failure?: Problem
  more: 'idle' | 'loading' | 'failed'
}

export interface SearchState {
  phase: 'idle' | 'streaming' | 'done' | 'error'
  field: SearchField
  query: string
  filter: ResultFilter
  targets: TargetsEvent | null
  plan: PlanState
  /** Only libraries that were requested (the summary bar lists these). */
  libraries: Record<number, LibraryResult>
  batches: Batch[]
  error: ApiError | null
  /** The last next-batch stream could not be opened or broke off. */
  batchFailed: boolean
}

type Action =
  | { type: 'reset' }
  | { type: 'start'; field: SearchField; query: string; filter: ResultFilter }
  | { type: 'batch-start'; batchNo: number }
  | { type: 'event'; batchNo: number; event: StreamEvent }
  | { type: 'error'; error: ApiError }
  | { type: 'batch-failed'; batchNo: number }
  | { type: 'more-start'; libraryId: number }
  | { type: 'more-done'; page: LibraryPage }
  | { type: 'more-failed'; libraryId: number }

const EMPTY_PLAN: PlanState = { order: [], entries: {} }

const IDLE: SearchState = {
  phase: 'idle',
  field: 'title',
  query: '',
  filter: 'all',
  targets: null,
  plan: EMPTY_PLAN,
  libraries: {},
  batches: [],
  error: null,
  batchFailed: false,
}

function newBatch(batchNo: number, kind: Batch['kind']): Batch {
  return { batchNo, kind, status: 'loading', requestedLibraries: [], rows: [] }
}

function nextBatchNo(state: SearchState): number {
  return state.batches.reduce((max, b) => Math.max(max, b.batchNo), 0) + 1
}

function updateBatch(state: SearchState, batchNo: number, change: (batch: Batch) => Batch): SearchState {
  return { ...state, batches: state.batches.map((b) => (b.batchNo === batchNo ? change(b) : b)) }
}

function updateLibrary(state: SearchState, id: number, change: (library: LibraryResult) => LibraryResult): SearchState {
  const library = state.libraries[id] ?? { status: 'loading', pages: [], loanRate: null, more: 'idle' }
  return { ...state, libraries: { ...state.libraries, [id]: change(library) } }
}

function addPage(library: LibraryResult, page: LibraryPage): LibraryResult {
  const pages = library.pages.some((p) => p.page === page.page) ? library.pages : [...library.pages, page]
  return { ...library, status: 'loaded', pages, loanRate: page.page === 1 ? page.loanRate : library.loanRate }
}

export function reducer(state: SearchState, action: Action): SearchState {
  switch (action.type) {
    case 'reset':
      return IDLE
    case 'start':
      return {
        ...IDLE,
        phase: 'streaming',
        field: action.field,
        query: action.query,
        filter: action.filter,
        batches: [newBatch(1, 'round')],
      }
    case 'batch-start':
      return { ...state, phase: 'streaming', batchFailed: false, batches: [...state.batches, newBatch(action.batchNo, 'round')] }
    case 'error':
      return { ...state, phase: 'error', error: action.error }
    case 'batch-failed':
      return {
        ...updateBatch(state, action.batchNo, (b) => ({ ...b, status: 'done' })),
        phase: 'done',
        batchFailed: true,
      }
    case 'more-start':
      return updateLibrary(state, action.libraryId, (l) => ({ ...l, more: 'loading' }))
    case 'more-failed':
      return updateLibrary(state, action.libraryId, (l) => ({ ...l, more: 'failed' }))
    case 'more-done': {
      // a library's "more" page becomes a new batch at the bottom [안]; round robin continues after it
      const batch: Batch = {
        batchNo: nextBatchNo(state),
        kind: 'library',
        status: 'done',
        requestedLibraries: [action.page.libraryId],
        rows: rowsOf(action.page),
      }
      const next = updateLibrary(state, action.page.libraryId, (l) => ({ ...addPage(l, action.page), more: 'idle' }))
      return { ...next, plan: markPage(next.plan, action.page), batches: [...next.batches, batch] }
    }
    case 'event':
      return onEvent(state, action.batchNo, action.event)
  }
}

function onEvent(state: SearchState, batchNo: number, event: StreamEvent): SearchState {
  switch (event.name) {
    case 'targets':
      return { ...state, targets: event.data, plan: initialPlan(event.data.libraries.map((l) => l.id)) }
    case 'library-requested': {
      const { libraryId } = event.data
      const next = updateLibrary(state, libraryId, (l) => (l.pages.length ? l : { ...l, status: 'loading' }))
      return updateBatch({ ...next, plan: markRequested(next.plan, libraryId) }, batchNo, (b) => ({
        ...b,
        requestedLibraries: [...b.requestedLibraries, libraryId],
      }))
    }
    case 'library-result': {
      const page = event.data
      const next = updateLibrary(state, page.libraryId, (l) => addPage(l, page))
      return updateBatch({ ...next, plan: markPage(next.plan, page) }, batchNo, (b) => ({
        ...b,
        rows: [...b.rows, ...rowsOf(page)],
      }))
    }
    case 'library-failed': {
      const { libraryId, problem } = event.data
      const next = updateLibrary(state, libraryId, (l) => ({
        ...l,
        status: l.pages.length ? 'loaded' : 'failed',
        failure: problem,
      }))
      return { ...next, plan: markFailed(next.plan, libraryId) }
    }
    case 'batch-done':
      return state
    case 'row-verdict':
      return { ...state, batches: applyRowVerdict(state.batches, event.data) }
    case 'done':
      return { ...updateBatch(state, batchNo, (b) => ({ ...b, status: 'done' })), phase: 'done' }
  }
}

function unexpected(error: unknown): ApiError {
  return error instanceof ApiError
    ? error
    : new ApiError(0, { title: 'Network error', status: 0, code: 'network_error', detail: String(error) })
}

interface Options {
  field: SearchField
  /** Normalized, valid query; null means no search. */
  query: string | null
  filter: ResultFilter
  scope: LibraryScope
  visit: VisitFilter
  regions: StoredRegions
  regionsReady: boolean
  resolveStoredRegions: () => Promise<boolean>
}

/**
 * Runs a search in batches. A new condition (field or query), filter, scope or region change drops every batch and
 * starts again from batch 1 [확정]; leaving the page aborts the open stream. `invalid_region` (regions changed on the
 * server) triggers one re-check of the stored regions per condition and, if they changed, a new search. The scope
 * and the visit filter only shape the plan (`targets`), so next batches need neither: their candidates come from
 * that plan. Next batches and a library's "more" use the condition of the search they belong to.
 */
export function useSearch({
  field,
  query,
  filter,
  scope,
  visit,
  regions,
  regionsReady,
  resolveStoredRegions,
}: Options) {
  const [state, dispatch] = useReducer(reducer, IDLE)
  const retriedFor = useRef<string | null>(null)
  const batchController = useRef<AbortController | null>(null)
  const residenceKey = regions.residence.join(',')
  const workKey = regions.work.join(',')

  useEffect(() => {
    batchController.current?.abort()
    if (!query || !regionsReady) {
      dispatch({ type: 'reset' })
      return
    }
    const controller = new AbortController()
    batchController.current = controller
    dispatch({ type: 'start', field, query, filter })
    const condition = `${field}:${query}`
    const params = {
      field,
      query,
      filter,
      scope,
      visit,
      residenceRegionIds: residenceKey ? residenceKey.split(',').map(Number) : [],
      workRegionIds: workKey ? workKey.split(',').map(Number) : [],
    }
    searchStream(params, (event) => dispatch({ type: 'event', batchNo: 1, event }), controller.signal).catch(
      async (error) => {
        if (controller.signal.aborted) return
        const apiError = unexpected(error)
        if (apiError.problem.code === 'invalid_region' && retriedFor.current !== condition) {
          retriedFor.current = condition
          // changed regions re-run this effect with the new ids
          const changed = await resolveStoredRegions().catch(() => false)
          if (changed || controller.signal.aborted) return
        }
        dispatch({ type: 'error', error: apiError })
      },
    )
    return () => controller.abort()
  }, [field, query, filter, scope, visit, residenceKey, workKey, regionsReady, resolveStoredRegions])

  /** 목록 맨 아래 "더 보기": the next libraries in plan order, or the next pages once every library was asked. */
  const loadNextBatch = useCallback(() => {
    if (state.phase === 'streaming' || !state.query) return
    const candidates = nextCandidates(state.plan, MAX_CANDIDATES)
    if (candidates.length === 0) return
    const batchNo = nextBatchNo(state)
    const controller = new AbortController()
    batchController.current = controller
    dispatch({ type: 'batch-start', batchNo })
    batchStream(
      { field: state.field, query: state.query, filter: state.filter, batchNo, candidates },
      (event) => dispatch({ type: 'event', batchNo, event }),
      controller.signal,
    ).catch(() => {
      if (!controller.signal.aborted) dispatch({ type: 'batch-failed', batchNo })
    })
  }, [state])

  /** A library's own "더 보기": its next page, added at the bottom as a new batch. */
  const loadLibraryMore = useCallback(
    async (libraryId: number) => {
      const library = state.libraries[libraryId]
      const entry = state.plan.entries[libraryId]
      if (!library || !entry || library.more === 'loading' || entry.status !== 'HasMore') return
      dispatch({ type: 'more-start', libraryId })
      try {
        const page = await fetchLibraryPage(libraryId, state.field, state.query, entry.nextPage)
        dispatch({ type: 'more-done', page })
      } catch {
        dispatch({ type: 'more-failed', libraryId })
      }
    },
    [state.libraries, state.plan, state.field, state.query],
  )

  return { state, loadNextBatch, loadLibraryMore }
}
