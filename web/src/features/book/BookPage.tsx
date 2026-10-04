import { useEffect, useState } from 'react'
import { Link, useNavigate, useParams, useSearchParams } from 'react-router'
import { ApiError, fetchBook } from '../../api/client'
import type { BookDetail, ResultFilter, VisitFilter } from '../../api/types'
import { useRegions } from '../region/RegionContext'
import { useLibraryScope } from '../search/useLibraryScope'
import { collectedRange, holdingBatchViews } from '../search/holdingView'
import { ResultList } from '../search/ResultList'
import { useHoldings } from '../search/useHoldings'
import styles from './BookPage.module.css'

/**
 * S3 book (flow.md): one book from 책 먼저 고르기 and the plan libraries holding it, asked on their detail pages
 * (same stream as 바로 보기 with one book). Address `/books/{id}` + filter, scope, visit [안]; regions come from this
 * browser.
 */
export function BookPage() {
  const { bookId = '' } = useParams()
  const [params] = useSearchParams()
  const { regions, libraryIds, ready } = useRegions()
  const { scope } = useLibraryScope(params)
  // a new book, filter, plan, region set or added libraries start over from a fresh page state
  const key = `${bookId}?${params}|${regions.residence.join(',')}|${regions.work.join(',')}|${libraryIds.join(',')}|${scope}|${ready}`
  return <BookView key={key} bookId={bookId} />
}

function BookView({ bookId }: { bookId: string }) {
  const [params, setParams] = useSearchParams()
  const navigate = useNavigate()
  const filter: ResultFilter = params.get('filter') === 'available' ? 'available' : 'all'
  const { scope } = useLibraryScope(params)
  const visit: VisitFilter = params.get('visit') === 'none' ? 'none' : 'any'
  const { regions, libraryIds, ready } = useRegions()
  const residenceKey = regions.residence.join(',')
  const workKey = regions.work.join(',')
  const libraryKey = libraryIds.join(',')

  const holdings = useHoldings()
  const { open } = holdings
  const [book, setBook] = useState<BookDetail | null>(null)
  const [error, setError] = useState<ApiError | null>(null)

  useEffect(() => {
    if (!ready) return
    const controller = new AbortController()
    const plan = {
      scope,
      visit,
      residenceRegionIds: residenceKey ? residenceKey.split(',').map(Number) : [],
      workRegionIds: workKey ? workKey.split(',').map(Number) : [],
      libraryIds: libraryKey ? libraryKey.split(',').map(Number) : [],
    }
    fetchBook(bookId, plan, controller.signal)
      .then((found) => {
        setBook(found)
        if (found.holdingLibraryCount > 0) {
          open(
            [
              {
                id: found.id,
                title: found.title,
                authorDisplay: found.authorDisplay,
                publisher: found.publisher,
                publishedOn: found.publishedOn,
                holdingLibraryCount: found.holdingLibraryCount,
                joinTier: 'OPEN',
                exactTitle: false,
              },
            ],
            { filter, ...plan },
          )
        }
      })
      .catch((failure: unknown) => {
        if (controller.signal.aborted) return
        setError(
          failure instanceof ApiError
            ? failure
            : new ApiError(0, { title: 'Network error', status: 0, code: 'network_error' }),
        )
      })
    return () => controller.abort()
  }, [bookId, ready, filter, scope, visit, residenceKey, workKey, libraryKey, open])

  const back = (
    <button type="button" className={styles.back} onClick={() => navigate(-1)}>
      ‹ 목록으로
    </button>
  )

  if (error) {
    return (
      <div className={styles.page}>
        {back}
        <div className={styles.empty}>
          {error.problem.code === 'book_not_found' ? (
            <>
              <p className={styles.emptyTitle}>책을 찾을 수 없어요</p>
              <Link className={styles.link} to="/">
                처음으로
              </Link>
            </>
          ) : (
            <p className={styles.emptyTitle}>책 정보를 불러오지 못했어요. 잠시 뒤 다시 시도해 주세요.</p>
          )}
        </div>
      </div>
    )
  }
  if (!book) {
    return (
      <div className={styles.page}>
        {back}
        <p className={styles.loading}>책 정보를 불러오는 중…</p>
      </div>
    )
  }

  const dates = book.libraries.map((l) => l.collectedAt?.slice(0, 10) ?? null).filter((d): d is string => d !== null).sort()
  const range = collectedRange(dates[0] ?? null, dates.at(-1) ?? null)
  const loadingBatch = holdings.state.batches.at(-1)
  const changeFilter = (next: ResultFilter) => {
    const updated = new URLSearchParams(params)
    if (next === 'available') updated.set('filter', 'available')
    else updated.delete('filter')
    setParams(updated, { replace: true })
  }

  return (
    <div className={styles.page}>
      {back}
      <header className={styles.book}>
        <h1 className={styles.title}>{book.title}</h1>
        <p className={styles.meta}>
          {[book.authorDisplay, book.publisher, book.publishedOn].filter(Boolean).join(' · ') || '저자·출판사 정보 없음'}
        </p>
        <p className={styles.collected}>
          {range ? `${range} 수집 목록` : '수집 목록'} · 보유 도서관 {book.holdingLibraryCount}곳
        </p>
      </header>
      {book.holdingLibraryCount === 0 ? (
        <p className={styles.loading}>조건에 맞는 도서관이 없어요</p>
      ) : (
        <ResultList
          batches={holdingBatchViews(holdings.state, filter)}
          filter={filter}
          onFilterChange={changeFilter}
          libraries={holdings.state.libraries}
          searchUrls={new Map()}
          remaining={holdings.remaining}
          loading={holdings.state.phase === 'streaming'}
          loadingLibraries={loadingBatch ? new Set(loadingBatch.requestedLibraries).size : 0}
          batchFailed={holdings.state.batchFailed}
          onMore={() => void holdings.loadNext()}
          moreText={`(남은 도서관 ${holdings.remaining}곳)`}
          finishedText="이 책을 가진 도서관을 모두 조회했습니다"
        />
      )}
    </div>
  )
}
