import { useEffect, useState } from 'react'
import { ApiError, fetchBook, type PlanParams } from '../../api/client'
import type { BookDetail, PickBook } from '../../api/types'
import { ResultRow } from '../search/ResultRow'
import { useHoldings } from '../search/useHoldings'
import { CoverImage } from './CoverImage'
import { pickLibraryRows, PICKS_HOLDING_LIMIT } from './pickLibraryRows'
import { videoUrl, type PicksList } from './picksLists'
import { PicksWindow } from './PicksWindow'
import styles from './PicksDialogs.module.css'
import { ChevronRight } from '../../components/icons'

type Props = {
  /** The tapped book with an e-book in my libraries (`bookId` set); null keeps the window closed. */
  book: PickBook | null
  /** The name of the book's field as the answer names it ("문학", or a new field's name). */
  categoryName: string | null
  plan: PlanParams
  onClose: () => void
  /** [책 검색 >>]: the page keeps its place, then opens the book screen (S3). */
  onSearch: (bookId: number) => void
  phone: boolean
  /** The list's line under the title (cycle 21 R6-1, R6-2). */
  list: PicksList
}

/**
 * S14 (R6-1~R6-8): the tapped cover, our e-book's title, the recommendation line "2026년 10월 사서추천 · 문학" (cycle
 * 14 [확정]), author and publisher (R6-2), [책 검색 >>] and at most three
 * library lines: only the first three plan holdings are asked (`holdingLimit=3`, user 2026-10-12), then sorted by
 * `pickLibraryRows`. The lines are S3's (`ResultRow`): a tap opens the library's book page in a new tab.
 */
export function PicksBookDialog({ book, categoryName, plan, onClose, onSearch, phone, list }: Props) {
  return (
    <PicksWindow
      open={book !== null}
      onOpenChange={(open) => !open && onClose()}
      title={book ? `${book.title} 대출 정보` : '대출 정보'}
      phone={phone}
      size="book"
    >
      {book?.bookId != null && (
        <BookBody
          key={book.bookId}
          book={book}
          categoryName={categoryName}
          bookId={book.bookId}
          plan={plan}
          onSearch={onSearch}
          list={list}
          phone={phone}
        />
      )}
    </PicksWindow>
  )
}

/** [영상 보기] (cycle 21 R6-2): Lee Dong-jin's video in a new tab; nothing for the other lists. */
export function VideoLink({ videoId }: { videoId: string | null }) {
  const url = videoUrl(videoId)
  if (!url) return null
  return (
    <a className={styles.videoLink} href={url} target="_blank" rel="noopener noreferrer">
      영상 보기
    </a>
  )
}

function BookBody({
  book,
  categoryName,
  bookId,
  plan,
  onSearch,
  list,
  phone,
}: {
  book: PickBook
  categoryName: string | null
  bookId: number
  plan: PlanParams
  onSearch: (bookId: number) => void
  list: PicksList
  phone: boolean
}) {
  const holdings = useHoldings()
  const { open } = holdings
  const [detail, setDetail] = useState<BookDetail | null>(null)
  const [error, setError] = useState<ApiError | null>(null)

  useEffect(() => {
    const controller = new AbortController()
    fetchBook(String(bookId), plan, controller.signal)
      .then((found) => {
        setDetail(found)
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
            { filter: 'all', ...plan, holdingLimit: PICKS_HOLDING_LIMIT },
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
  }, [bookId, plan, open])

  const head = (
    <div className={styles.bookHead}>
      <CoverImage className={styles.bookCover} url={book.coverUrl} title={book.title} />
      <div className={styles.bookText}>
        {/* the shown name (cycle 21 R6-3): our title without edition marks; the list's own name on a line if other */}
        <h2 className={styles.bookTitle}>{book.title}</h2>
        {book.listTitle && <p className={styles.bookListTitle}>목록 이름 「{book.listTitle}」</p>}
        <p className={styles.bookPick}>{list.pickLine(book, categoryName)}</p>
        {detail && (
          <p className={styles.bookMeta}>
            {[detail.authorDisplay, detail.publisher].filter(Boolean).join(' · ') || '저자·출판사 정보 없음'}
          </p>
        )}
        {/* [영상 보기] (R6-2): next to "책 검색 >>" on PC and tablets, on the line below on phones */}
        <div className={styles.bookActions} data-phone={phone || undefined}>
          {!error && (
            <button type="button" className={styles.searchButton} onClick={() => onSearch(bookId)}>
              책 검색 <ChevronRight size={6} />
            </button>
          )}
          <VideoLink videoId={book.videoId} />
        </div>
      </div>
    </div>
  )

  if (error) {
    // the same words as the book screen (S3) [확정] (handoff decision #4, 2026-10-12)
    return (
      <div className={styles.book}>
        {head}
        <div className={styles.problem} role="alert">
          {error.problem.code === 'book_not_found' ? (
            <>
              <p className={styles.problemTitle}>책을 찾을 수 없어요</p>
              <p className={styles.problemNote}>도서관 목록을 새로 받으면서 빠진 책일 수 있어요. 다시 검색해 주세요.</p>
            </>
          ) : (
            <p className={styles.problemTitle}>책 정보를 불러오지 못했어요. 잠시 뒤 다시 시도해 주세요.</p>
          )}
        </div>
      </div>
    )
  }

  const { state } = holdings
  const planIndex = new Map(state.libraries.map((library, index) => [library.id, index]))
  const libraries = new Map(state.libraries.map((library) => [library.id, library]))
  const rows = pickLibraryRows(
    state.batches.flatMap((batch) => batch.rows),
    planIndex,
  )
  const checking = detail === null || (detail.holdingLibraryCount > 0 && state.phase !== 'done')

  return (
    <div className={styles.book}>
      {head}
      {detail !== null && detail.holdingLibraryCount > 0 && (
        // design A: what the lines are (R6-3: the first three searched libraries in plan order)
        <p className={styles.rowsLabel}>검색하는 도서관 중 앞의 3곳</p>
      )}
      <div className={styles.rows}>
        {rows.map((row) => (
          <ResultRow
            key={`${row.libraryId}-${row.rank}`}
            row={row}
            library={libraries.get(row.libraryId)}
            searchUrl={undefined}
            screen="picks"
          />
        ))}
        {checking && (
          <p className={styles.checking} role="status">
            확인 중…
          </p>
        )}
        {!checking && rows.length === 0 && (
          // the shared picks cache may be up to 10 minutes behind the catalog [안] (handoff step 7-8)
          <p className={styles.checking}>
            {state.batchFailed ? '대출 정보를 확인하지 못했어요. 잠시 뒤 다시 시도해 주세요.' : '조건에 맞는 도서관이 없어요'}
          </p>
        )}
      </div>
    </div>
  )
}
