import { useCallback, useEffect, useState } from 'react'
import { ApiError, fetchBooks, type BookQuery } from '../../api/client'
import type { BookItem, BookPage, BookSort, ResultFilter } from '../../api/types'
import { collectedRange, holdingBatchViews } from './holdingView'
import { ResultList } from './ResultList'
import { SORT_LABELS, SORTS } from './searchCondition'
import { useHoldings } from './useHoldings'
import styles from './SearchPage.module.css'

type Props = {
  /** Page 1 of the catalog search. The parent remounts this with a new key for a new search [확정]. */
  query: BookQuery
  filter: ResultFilter
  onFilterChange: (filter: ResultFilter) => void
  onSortChange: (sort: BookSort) => void
  /** "도서관에서 직접 찾기" of the empty result (spec 3.7). */
  onDirect: () => void
  /** `invalid_region`: the stored regions are checked again; true when they changed (a new search follows). */
  onInvalidRegion: () => Promise<boolean>
}

/**
 * 바로 보기 (default view, flow.md S1): books from the collected catalog in their order, then the plan libraries holding
 * them, asked on their detail pages in batches (ADR-0030). "더 보기" asks the holdings not asked yet; when the books
 * of this page are done, the next book page is fetched and its holdings are asked [안].
 */
export function InstantResults({ query, filter, onFilterChange, onSortChange, onDirect, onInvalidRegion }: Props) {
  const holdings = useHoldings()
  const { open } = holdings
  const [books, setBooks] = useState<{ items: BookItem[]; last: BookPage } | null>(null)
  const [error, setError] = useState<ApiError | null>(null)
  const [pageLoading, setPageLoading] = useState(false)
  const [pageFailed, setPageFailed] = useState(false)

  const planOptions = useCallback(
    () => ({
      filter,
      scope: query.scope,
      visit: query.visit,
      residenceRegionIds: query.residenceRegionIds,
      workRegionIds: query.workRegionIds,
      libraryIds: query.libraryIds,
    }),
    [filter, query],
  )

  useEffect(() => {
    const controller = new AbortController()
    let retried = false
    const load = () =>
      fetchBooks({ ...query, page: 1 }, controller.signal)
        .then((page) => {
          setBooks({ items: page.items, last: page })
          if (page.items.length > 0) open(page.items, planOptions())
        })
        .catch(async (failure: unknown) => {
          if (controller.signal.aborted) return
          const apiError = asApiError(failure)
          if (apiError.problem.code === 'invalid_region' && !retried) {
            retried = true
            if (await onInvalidRegion().catch(() => false)) return
          }
          setError(apiError)
        })
    void load()
    return () => controller.abort()
  }, [query, planOptions, open, onInvalidRegion])

  const loadMore = async () => {
    if (holdings.loadNext() || !books?.last.hasMore) return
    setPageLoading(true)
    setPageFailed(false)
    try {
      const page = await fetchBooks({ ...query, page: books.last.page + 1 })
      setBooks({ items: [...books.items, ...page.items], last: page })
      if (page.items.length > 0) open(page.items, planOptions())
    } catch {
      setPageFailed(true)
    } finally {
      setPageLoading(false)
    }
  }

  if (error) {
    return (
      <p className={styles.notice} data-kind="error" role="alert">
        {error.problem.code === 'invalid_region'
          ? '저장된 내 지역을 확인하지 못했어요. 내 지역을 다시 골라 주세요'
          : '검색하지 못했어요. 잠시 뒤 다시 시도해 주세요.'}
      </p>
    )
  }
  if (!books) return <p className={styles.searching}>수집 목록에서 찾는 중…</p>

  const range = collectedRange(books.last.collectedFrom, books.last.collectedTo)
  if (books.items.length === 0) {
    return (
      <div className={styles.allEmpty}>
        <p className={styles.allEmptyTitle}>
          조건에 맞는 책이 수집 목록에 없어요{range ? ` (${range})` : ''}
        </p>
        <button type="button" className={styles.directButton} onClick={onDirect}>
          도서관에서 직접 찾기
        </button>
        <p className={styles.allEmptyLibraries}>도서관 사이트에서 제목 하나로 찾아요. 비슷한 제목의 다른 책이 섞일 수 있어요.</p>
      </div>
    )
  }

  const views = holdingBatchViews(holdings.state, filter)
  const streaming = holdings.state.phase === 'streaming' || pageLoading
  const moreBooks = books.last.hasMore
  const remaining = holdings.remaining + (moreBooks ? 1 : 0)
  const loadingBatch = holdings.state.batches.at(-1)
  const noHoldings = !streaming && holdings.state.order.length === 0

  return (
    <>
      <ResultsHeader
        range={range}
        total={books.last.totalCount}
        sort={query.sort}
        onSortChange={onSortChange}
      />
      {noHoldings ? (
        <p className={styles.searching}>조건에 맞는 도서관이 없어요</p>
      ) : (
        <ResultList
          batches={views}
          filter={filter}
          onFilterChange={onFilterChange}
          libraries={holdings.state.libraries}
          searchUrls={new Map()}
          remaining={remaining}
          loading={streaming}
          loadingLibraries={loadingBatch ? new Set(loadingBatch.requestedLibraries).size : 0}
          loadingText={pageLoading ? '다음 책을 찾는 중' : undefined}
          batchFailed={holdings.state.batchFailed || pageFailed}
          onMore={() => void loadMore()}
          moreText={
            holdings.remaining > 0 ? `(남은 도서관 줄 ${holdings.remaining}개)` : moreBooks ? '(다음 책)' : undefined
          }
          finishedText="찾은 책을 가진 도서관을 모두 조회했습니다"
        />
      )}
    </>
  )
}

/** "9월 30일 기준 수집 목록 · 책 N권" and the sort on the right (flow.md S1). */
export function ResultsHeader({
  range,
  total,
  sort,
  onSortChange,
}: {
  range: string | null
  total: number
  sort: BookSort
  onSortChange: (sort: BookSort) => void
}) {
  return (
    <div className={styles.resultsHeader}>
      <p className={styles.collected}>
        {range ? `${range} 수집 목록` : '수집 목록'} · 책 {total.toLocaleString('ko-KR')}권
      </p>
      <label className={styles.sort}>
        <span className="visually-hidden">정렬</span>
        <select value={sort} onChange={(event) => onSortChange(event.target.value as BookSort)}>
          {SORTS.map((value) => (
            <option key={value} value={value}>
              {SORT_LABELS[value]}
            </option>
          ))}
        </select>
      </label>
    </div>
  )
}

function asApiError(error: unknown): ApiError {
  return error instanceof ApiError
    ? error
    : new ApiError(0, { title: 'Network error', status: 0, code: 'network_error', detail: String(error) })
}
