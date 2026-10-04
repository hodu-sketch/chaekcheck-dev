import { useMemo } from 'react'
import type { Eligibility, LibraryScope, ResultFilter, SearchField, TargetLibrary, VisitFilter } from '../../api/types'
import { useRegions } from '../region/RegionContext'
import { LibrarySummaryBar } from './LibrarySummaryBar'
import { ResultList, type BatchView } from './ResultList'
import { SortSelect } from './SortSelect'
import { remainingLibraries } from './searchPlan'
import { DIRECT_REASON } from './searchSettings'
import { applyFilter, sortBatch } from './sortBatch'
import { useSearch } from './useSearch'
import styles from './SearchPage.module.css'

type Props = {
  field: SearchField
  /** Normalized, valid text of the one condition. */
  query: string
  filter: ResultFilter
  scope: LibraryScope
  visit: VisitFilter
  onFilterChange: (filter: ResultFilter) => void
  onEditRegions: () => void
}

/**
 * 도서관에서 직접 찾기 (spec 3.7): the cycle 03 site search as it was, with one condition (the first filled field).
 * Batches, the summary bar, "더 보기" and the empty message are unchanged [확정].
 */
export function DirectResults({ field, query, filter, scope, visit, onFilterChange, onEditRegions }: Props) {
  const { regions, libraryIds, ready, resolveStoredRegions } = useRegions()
  const { state, loadNextBatch, loadLibraryMore } = useSearch({
    field,
    query,
    filter,
    scope,
    visit,
    regions,
    libraryIds,
    regionsReady: ready,
    resolveStoredRegions,
  })

  const targets = useMemo(() => state.targets?.libraries ?? [], [state.targets])
  const view = useMemo(() => {
    const planIndex = new Map(targets.map((l, index) => [l.id, index]))
    const eligibility = new Map<number, Eligibility>(targets.map((l) => [l.id, l.eligibility]))
    const cautioned = new Set(targets.filter((l) => l.joinCaution).map((l) => l.id))
    const visitRequired = new Set(targets.filter((l) => l.visitRequired).map((l) => l.id))
    // the first stream's targets; the next batches keep them (C16)
    const chosen = new Set(targets.filter((l) => l.chosen).map((l) => l.id))
    const loanRates = new Map<number, number | null>()
    const searchUrls = new Map<number, string>()
    for (const [id, library] of Object.entries(state.libraries)) {
      loanRates.set(Number(id), library.loanRate)
      if (library.pages[0]) searchUrls.set(Number(id), library.pages[0].librarySearchUrl)
    }
    const answered = new Set<number>()
    const batches: BatchView[] = state.batches.map((batch) => {
      for (const id of batch.requestedLibraries) answered.add(id)
      return {
        batch,
        sorted: applyFilter(sortBatch(batch.rows, { planIndex, eligibility, cautioned, visitRequired, loanRates, chosen }), filter),
        answeredSoFar: answered.size,
      }
    })
    const rowCount = state.batches.reduce((sum, batch) => sum + batch.rows.length, 0)
    return { batches, searchUrls, rowCount }
  }, [state.batches, state.libraries, targets, filter])

  // requested libraries only, those with results first [확정]
  const requested = useMemo(() => {
    const list = targets.filter((l) => state.libraries[l.id])
    const hasRows = (l: TargetLibrary) => (state.libraries[l.id]?.pages.some((p) => p.items.length > 0) ? 0 : 1)
    return [...list].sort((a, b) => hasRows(a) - hasRows(b))
  }, [targets, state.libraries])

  const remaining = remainingLibraries(state.plan)
  const streaming = state.phase === 'streaming'
  const loadingBatch = state.batches.at(-1)
  const noQualified =
    state.targets?.regionFilterApplied &&
    !targets.some((l) => l.priorityGroup === 'MY_SIGUNGU' || l.priorityGroup === 'MY_SIDO')
  const noTargets = state.targets !== null && targets.length === 0
  const allEmpty = state.phase === 'done' && remaining === 0 && view.rowCount === 0 && requested.length > 0
  const firstBatchWaiting = streaming && state.batches.length === 1 && view.rowCount === 0

  return (
    <>
      {(noQualified || state.error) && (
        <div className={styles.notices}>
          {noQualified && (
            <p className={styles.notice} role="status">
              선택한 지역에서 자격이 확인된 도서관이 없습니다
            </p>
          )}
          {state.error && <SearchError code={state.error.problem.code} onEditRegions={onEditRegions} />}
        </div>
      )}
      {state.targets && (
        <section className={styles.results}>
          {/* dimmed with its reason; no guide line above the results [확정] (user: "흐리게 이것만") */}
          <div className={styles.resultsHeader} data-direct>
            <SortSelect value="default" disabled describedBy="direct-sort-reason" />
            <span id="direct-sort-reason" className={styles.dimReason}>
              {DIRECT_REASON}
            </span>
          </div>
          {requested.length > 0 && (
            <LibrarySummaryBar
              libraries={requested}
              results={state.libraries}
              hasMore={(id) => state.plan.entries[id]?.status === 'HasMore'}
              onMore={loadLibraryMore}
            />
          )}
          {firstBatchWaiting && <p className={styles.searching}>도서관에서 찾는 중…</p>}
          {noTargets && (
            <p className={styles.searching}>
              {scope === 'joinable' ? '가입할 수 있다고 확인된 도서관이 없습니다' : '조회할 수 있는 도서관이 없습니다'}
            </p>
          )}
          {allEmpty ? (
            <div className={styles.allEmpty}>
              <p className={styles.allEmptyTitle}>{allEmptyMessage(state.field, state.query, requested.length)}</p>
              <p className={styles.allEmptyLibraries}>{requested.map((l) => l.name).join(' · ')}</p>
            </div>
          ) : (
            !firstBatchWaiting &&
            !noTargets && (
              <ResultList
                batches={view.batches}
                filter={filter}
                onFilterChange={onFilterChange}
                libraries={targets}
                searchUrls={view.searchUrls}
                remaining={remaining}
                loading={streaming}
                loadingLibraries={loadingBatch ? new Set(loadingBatch.requestedLibraries).size : 0}
                batchFailed={state.batchFailed}
                onMore={loadNextBatch}
              />
            )
          )}
        </section>
      )}
    </>
  )
}

/** Every library was asked and none had a row (flow.md; author and publisher wording [안]). */
function allEmptyMessage(field: SearchField, query: string, libraries: number): string {
  const where = `조회한 도서관 ${libraries}곳에`
  if (field === 'author') return `${where} 저자 '${query}'의 전자책이 없습니다`
  if (field === 'publisher') return `${where} 출판사 '${query}'의 전자책이 없습니다`
  return `${where} 이 제목의 전자책이 없습니다`
}

export function SearchError({ code, onEditRegions }: { code: string; onEditRegions: () => void }) {
  if (code === 'invalid_region') {
    return (
      <p className={styles.notice} data-kind="error" role="alert">
        저장된 내 지역을 확인하지 못했어요. 내 지역을 다시 골라 주세요 ·{' '}
        <button type="button" className={styles.noticeAction} onClick={onEditRegions}>
          설정
        </button>
      </p>
    )
  }
  return (
    <p className={styles.notice} data-kind="error" role="alert">
      검색하지 못했어요. 잠시 뒤 다시 시도해 주세요.
    </p>
  )
}
