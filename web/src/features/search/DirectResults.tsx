import { useEffect, useMemo, useRef } from 'react'
import { createPortal } from 'react-dom'
import type { ApiError } from '../../api/client'
import type { Eligibility, LibraryScope, ResultFilter, SearchField, TargetLibrary, VisitFilter } from '../../api/types'
import { InfoTip } from '../../components/InfoTip'
import { BLOCKED_TEXT, blockedBy } from './blocked'
import { LibraryCountButton } from './LibrarySummaryBar'
import { ResultList, type BatchView, type ResultSearchContext, type UncheckedLibrary } from './ResultList'
import listStyles from './ResultList.module.css'
import { uncheckedText } from './resultFilter'
import { DIRECT_REASON } from './searchSettings'
import { SortSelect } from './SortSelect'
import { remainingLibraries } from './searchPlan'
import { applyFilter, sortBatch } from './sortBatch'
import { useSearch } from './useSearch'
import type { SearchedWith } from './useSearchedWith'
import styles from './SearchPage.module.css'

type Props = {
  field: SearchField
  /** Normalized, valid text of the one condition. */
  query: string
  filter: ResultFilter
  scope: LibraryScope
  visit: VisitFilter
  /** The regions and added libraries the search was started with (cycle 22 D6), not the ones saved since. */
  searchedWith: SearchedWith
  /** Re-checks the stored region ids after `invalid_region`; true when they changed (the snapshot follows). */
  onInvalidRegion: () => Promise<boolean>
  onFilterChange: (filter: ResultFilter) => void
  onEditRegions: () => void
  /** The first stream's plan has come: its size (cycle 22 D5, R3-3), the whole plan unlike 바로 보기's. */
  onPlan?: (count: number) => void
  /**
   * The place for the "N곳" button (the search page's, see `countSlot` there), which replaces the row of libraries on
   * every width (user requests 2026-10-05); null while that place is not there yet.
   */
  countSlot: HTMLElement | null
  /** The search's regions and the buttons inside the results (cycle 22 R5, R7, R8). */
  searchContext?: ResultSearchContext
}

/**
 * 도서관에서 직접 찾기 (spec 3.7): the cycle 03 site search as it was, with one condition (the first filled field).
 * Batches, the summary bar, "더 보기" and the empty message are unchanged [확정].
 */
export function DirectResults({
  field,
  query,
  filter,
  scope,
  visit,
  searchedWith,
  onInvalidRegion,
  onFilterChange,
  onEditRegions,
  onPlan,
  countSlot,
  searchContext,
}: Props) {
  const regions = useMemo(
    () => ({ residence: searchedWith.residence, work: searchedWith.work }),
    [searchedWith.residence, searchedWith.work],
  )
  const { state, loadNextBatch, loadLibraryMore } = useSearch({
    field,
    query,
    filter,
    scope,
    visit,
    regions,
    libraryIds: searchedWith.libraryIds,
    regionsReady: true,
    resolveStoredRegions: onInvalidRegion,
  })

  const targets = useMemo(() => state.targets?.libraries ?? [], [state.targets])
  const onPlanRef = useRef(onPlan)
  useEffect(() => {
    onPlanRef.current = onPlan
  }, [onPlan])
  useEffect(() => {
    if (state.targets) onPlanRef.current?.(state.targets.libraries.length)
  }, [state.targets])
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
    // libraries whose page could not be read (cycle 20 review #1): one line each in 「확인하지 못한 도서관」, which opens
    // the library's site (the search page's address is only known from a page that was read)
    const failed = new Set(
      Object.entries(state.libraries)
        .filter(([, library]) => library.status === 'failed')
        .map(([id]) => Number(id)),
    )
    const uncheckedLibraries: UncheckedLibrary[] = targets
      .filter((l) => failed.has(l.id))
      .map((library) => ({ library, url: library.homepageUrl }))
    const answered = new Set<number>()
    const batches: BatchView[] = state.batches.map((batch) => {
      for (const id of batch.requestedLibraries) answered.add(id)
      return {
        batch,
        sorted: applyFilter(sortBatch(batch.rows, { planIndex, eligibility, cautioned, visitRequired, loanRates, chosen }), filter),
        answeredSoFar: answered.size,
        uncheckedSoFar: [...answered].filter((id) => failed.has(id)).length,
      }
    })
    const rowCount = state.batches.reduce((sum, batch) => sum + batch.rows.length, 0)
    return { batches, searchUrls, rowCount, uncheckedLibraries }
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
          {state.error && <SearchError error={state.error} onEditRegions={onEditRegions} />}
        </div>
      )}
      {state.targets && (
        <section className={styles.results}>
          {requested.length > 0 &&
            countSlot &&
            createPortal(
              <LibraryCountButton
                libraries={requested}
                results={state.libraries}
                hasMore={(id) => state.plan.entries[id]?.status === 'HasMore'}
                onMore={loadLibraryMore}
              />,
              countSlot,
            )}
          {firstBatchWaiting && <p className={styles.searching}>도서관에서 찾는 중…</p>}
          {noTargets && (
            <p className={styles.searching}>
              {scope === 'joinable' ? '가입할 수 있다고 확인된 도서관이 없습니다' : '조회할 수 있는 도서관이 없습니다'}
            </p>
          )}
          {allEmpty ? (
            <div className={styles.allEmpty}>
              {/* the failed libraries are counted apart (cycle 20 handoff 1-13 [확정]) */}
              <p className={styles.allEmptyTitle}>
                {allEmptyMessage(state.field, state.query, requested.length - view.uncheckedLibraries.length)}
                {view.uncheckedLibraries.length > 0 && ` · ${uncheckedText(view.uncheckedLibraries.length)}`}
              </p>
              <p className={styles.allEmptyLibraries}>{requested.map((l) => l.name).join(' · ')}</p>
            </div>
          ) : (
            !firstBatchWaiting &&
            !noTargets && (
              <ResultList
                screen="direct_results"
                grouped
                tags
                searchContext={searchContext}
                batches={view.batches}
                filter={filter}
                onFilterChange={onFilterChange}
                toolbarEnd={
                  // wider screens; on phones this line is hidden and NoSort sits on the search line instead
                  <>
                    {/* dimmed with its reason [확정] (user: "흐리게 이것만") */}
                    <span id="direct-sort-reason" className={listStyles.dimReason}>
                      {DIRECT_REASON}
                    </span>
                    <SortSelect value="default" disabled describedBy="direct-sort-reason" />
                  </>
                }
                libraries={targets}
                searchUrls={view.searchUrls}
                remaining={remaining}
                loading={streaming}
                loadingLibraries={loadingBatch ? new Set(loadingBatch.requestedLibraries).size : 0}
                batchFailed={state.batchFailed}
                blocked={state.blocked}
                onMore={loadNextBatch}
                uncheckedLibraries={view.uncheckedLibraries}
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

/** [확정] (cycle 08 #5): why the direct site search has no sort, behind the ⓘ on phones. */
export const NO_SORT_REASON = '도서관 사이트 검색에서는 정렬을 쓸 수 없어요'

/**
 * Phones, direct search (cycle 08 #5 [확정]): where the sort sits on the search line, a dimmed "정렬 없음" whose ⓘ
 * tells why.
 */
export function NoSort() {
  return (
    <span className={listStyles.noSort}>
      정렬 없음
      <InfoTip label="정렬 없음 안내" text={NO_SORT_REASON} />
    </span>
  )
}

export function SearchError({ error, onEditRegions }: { error: ApiError; onEditRegions: () => void }) {
  const code = error.problem.code
  const blocked = blockedBy(error)
  if (blocked) {
    return (
      <p className={styles.notice} data-kind="blocked" role="alert">
        {BLOCKED_TEXT[blocked]}
      </p>
    )
  }
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
