import { useEffect, useLayoutEffect, useRef, useState } from 'react'
import { ApiError, fetchBook, type PlanParams } from '../../api/client'
import type { BookDetail, PickBook } from '../../api/types'
import { ResultRow } from '../search/ResultRow'
import { useHoldings } from '../search/useHoldings'
import { CoverImage } from './CoverImage'
import { latestRowPerLibrary, pickLibraryRows, PICKS_HOLDING_STEPS } from './pickLibraryRows'
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
  // which of PICKS_HOLDING_STEPS is asked now
  const [step, setStep] = useState(0)

  useEffect(() => {
    const controller = new AbortController()
    fetchBook(String(bookId), plan, controller.signal)
      .then((found) => setDetail(found))
      .catch((failure: unknown) => {
        if (controller.signal.aborted) return
        setError(
          failure instanceof ApiError
            ? failure
            : new ApiError(0, { title: 'Network error', status: 0, code: 'network_error' }),
        )
      })
    return () => controller.abort()
  }, [bookId, plan])

  useEffect(() => {
    if (!detail || detail.holdingLibraryCount === 0) return
    open(
      [
        {
          id: detail.id,
          title: detail.title,
          authorDisplay: detail.authorDisplay,
          publisher: detail.publisher,
          publishedOn: detail.publishedOn,
          holdingLibraryCount: detail.holdingLibraryCount,
          joinTier: 'OPEN',
          exactTitle: false,
        },
      ],
      { filter: 'all', ...plan, holdingLimit: PICKS_HOLDING_STEPS[step] },
    )
  }, [detail, step, plan, open])

  // none of the libraries asked can lend it now, and there may be more: ask the next step
  const { state } = holdings
  const asked = latestRowPerLibrary(state.batches.flatMap((batch) => batch.rows))
  const more =
    state.phase === 'done' &&
    !state.batchFailed &&
    step < PICKS_HOLDING_STEPS.length - 1 &&
    state.libraries.length >= PICKS_HOLDING_STEPS[step] &&
    !asked.some((row) => row.availability === 'AVAILABLE')
  // adjusted while rendering (no effect): the next render opens the wider step, whose `targets` then name more libraries
  if (more) setStep(step + 1)

  // phones: [영상 보기]'s right edge on the right end of the purple recommendation line's longest line, so the two
  // purple texts share one vertical line (user 2026-10-17: "오른쪽 끝이긴 한데 세로선으로 안 맞잖아 위의 또다른 보라
  // 글자랑 이거 정렬 맞추게 해줘"). The line wraps by words, so its box is wider than its text: measured, not CSS.
  const pickRef = useRef<HTMLParagraphElement>(null)
  const actionsRef = useRef<HTMLDivElement>(null)
  const [actionsWidth, setActionsWidth] = useState<number | null>(null)
  const hasVideo = videoUrl(book.videoId) !== null
  useLayoutEffect(() => {
    const pick = pickRef.current
    const actions = actionsRef.current
    if (!phone || !hasVideo || !pick || !actions) {
      setActionsWidth(null)
      return
    }
    const measure = () => {
      const range = document.createRange()
      range.selectNodeContents(pick)
      const right = Math.max(...Array.from(range.getClientRects(), (r) => r.right))
      // the widest of the two: when both do not fit on one line, [영상 보기] goes to the next line, at the same right edge
      const needed = Math.max(...Array.from(actions.children, (child) => child.getBoundingClientRect().width))
      const width = right - actions.getBoundingClientRect().left
      setActionsWidth(Number.isFinite(width) && width >= needed ? Math.round(width) : null)
    }
    measure()
    const observer = new ResizeObserver(measure)
    observer.observe(pick)
    return () => observer.disconnect()
  }, [phone, hasVideo, detail, error])

  const head = (
    <div className={styles.bookHead}>
      <CoverImage className={styles.bookCover} url={book.coverUrl} title={book.title} />
      <div className={styles.bookText}>
        {/* the shown name (cycle 21 R6-3): our title without edition marks. No "목록 이름" line (user 2026-10-17:
            "목록 이름 아예 다 빼줘 무의미한것 같아"; `listTitle` still comes from the API) */}
        <h2 className={styles.bookTitle}>{book.title}</h2>
        <p ref={pickRef} className={styles.bookPick}>
          {list.pickLine(book, categoryName)}
        </p>
        {detail && (
          <p className={styles.bookMeta}>
            {[detail.authorDisplay, detail.publisher].filter(Boolean).join(' · ') || '저자·출판사 정보 없음'}
          </p>
        )}
        {/* [영상 보기] (R6-2): next to "책 검색 >>" on PC and tablets, at the right end of the same line on phones */}
        <div
          ref={actionsRef}
          className={styles.bookActions}
          data-phone={phone || undefined}
          style={actionsWidth !== null ? { width: actionsWidth, alignSelf: 'flex-start' } : undefined}
        >
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

  const planIndex = new Map(state.libraries.map((library, index) => [library.id, index]))
  const libraries = new Map(state.libraries.map((library) => [library.id, library]))
  const rows = pickLibraryRows(asked, planIndex)
  const checking = detail === null || (detail.holdingLibraryCount > 0 && (state.phase !== 'done' || more))

  return (
    <div className={styles.book}>
      {head}
      {detail !== null && detail.holdingLibraryCount > 0 && (
        // design A: what the lines are (R6-3); after more steps, how many were asked
        <p className={styles.rowsLabel}>
          {step === 0 ? '검색하는 도서관 중 앞의 3곳' : `검색하는 도서관 ${state.libraries.length}곳 중 3곳`}
        </p>
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
