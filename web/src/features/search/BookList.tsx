import { useEffect, useState } from 'react'
import { Link } from 'react-router'
import { ApiError, fetchBooks, type BookQuery } from '../../api/client'
import type { BookItem, BookPage, BookSort } from '../../api/types'
import { collectedRange } from './holdingView'
import { ResultsHeader } from './InstantResults'
import styles from './BookList.module.css'
import pageStyles from './SearchPage.module.css'

type Props = {
  query: BookQuery
  /** Address part the book page keeps: filter, scope, visit. */
  bookLinkSearch: string
  onSortChange: (sort: BookSort) => void
  onDirect: () => void
  onInvalidRegion: () => Promise<boolean>
}

/**
 * 책 먼저 고르기 (spec 3.8, flow.md S1 예시 2): the book list only. No library is asked here [확정]; choosing a book
 * opens S3 (`/books/{id}`). Same order as 바로 보기 [안].
 */
export function BookList({ query, bookLinkSearch, onSortChange, onDirect, onInvalidRegion }: Props) {
  const [books, setBooks] = useState<{ items: BookItem[]; last: BookPage } | null>(null)
  const [error, setError] = useState<ApiError | null>(null)
  const [loadingMore, setLoadingMore] = useState(false)
  const [moreFailed, setMoreFailed] = useState(false)

  useEffect(() => {
    const controller = new AbortController()
    let retried = false
    const load = () =>
      fetchBooks({ ...query, page: 1 }, controller.signal)
        .then((page) => setBooks({ items: page.items, last: page }))
        .catch(async (failure: unknown) => {
          if (controller.signal.aborted) return
          const apiError =
            failure instanceof ApiError
              ? failure
              : new ApiError(0, { title: 'Network error', status: 0, code: 'network_error' })
          if (apiError.problem.code === 'invalid_region' && !retried) {
            retried = true
            if (await onInvalidRegion().catch(() => false)) return
          }
          setError(apiError)
        })
    void load()
    return () => controller.abort()
  }, [query, onInvalidRegion])

  const loadMore = async () => {
    if (!books?.last.hasMore) return
    setLoadingMore(true)
    setMoreFailed(false)
    try {
      const page = await fetchBooks({ ...query, page: books.last.page + 1 })
      setBooks({ items: [...books.items, ...page.items], last: page })
    } catch {
      setMoreFailed(true)
    } finally {
      setLoadingMore(false)
    }
  }

  if (error) {
    return (
      <p className={pageStyles.notice} data-kind="error" role="alert">
        검색하지 못했어요. 잠시 뒤 다시 시도해 주세요.
      </p>
    )
  }
  if (!books) return <p className={pageStyles.searching}>수집 목록에서 찾는 중…</p>

  const range = collectedRange(books.last.collectedFrom, books.last.collectedTo)
  if (books.items.length === 0) {
    return (
      <div className={pageStyles.allEmpty}>
        <p className={pageStyles.allEmptyTitle}>
          조건에 맞는 책이 수집 목록에 없어요{range ? ` (${range})` : ''}
        </p>
        <button type="button" className={pageStyles.directButton} onClick={onDirect}>
          도서관에서 직접 찾기
        </button>
      </div>
    )
  }

  return (
    <>
      <ResultsHeader range={range} total={books.last.totalCount} sort={query.sort} onSortChange={onSortChange} />
      <ol className={styles.list} aria-label="찾은 책">
        {books.items.map((book) => (
          <li key={book.id}>
            <Link className={styles.book} to={`/books/${book.id}${bookLinkSearch ? `?${bookLinkSearch}` : ''}`}>
              <span className={styles.title}>{book.title}</span>
              <span className={styles.meta}>
                {[book.authorDisplay, book.publisher, book.publishedOn?.slice(0, 4)].filter(Boolean).join(' · ') ||
                  '저자·출판사 정보 없음'}
              </span>
              <span className={styles.count}>보유 도서관 {book.holdingLibraryCount}곳</span>
              <span className={styles.chevron} aria-hidden="true">
                ›
              </span>
            </Link>
          </li>
        ))}
      </ol>
      <div className={styles.bottom}>
        {moreFailed && (
          <p className={styles.failed} role="alert">
            더 찾지 못했어요. 다시 시도해 주세요.
          </p>
        )}
        {books.last.hasMore ? (
          <button type="button" className={styles.more} onClick={() => void loadMore()} disabled={loadingMore}>
            {loadingMore ? '찾는 중…' : '더 보기'}
          </button>
        ) : (
          <p className={styles.finished}>찾은 책을 모두 보여 드렸어요</p>
        )}
      </div>
    </>
  )
}
