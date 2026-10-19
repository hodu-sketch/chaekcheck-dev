import { Popover } from 'radix-ui'
import { useEffect, useState } from 'react'
import { ApiError, fetchBook, type PlanParams } from '../../api/client'
import type { BookDetail, PickBook } from '../../api/types'
import { ResultRow } from '../search/ResultRow'
import { useHoldings } from '../search/useHoldings'
import { CoverImage } from './CoverImage'
import { latestRowPerLibrary, pickLibraryRows, PICKS_HOLDING_STEPS } from './pickLibraryRows'
import { videoUrl } from './picksLists'
import { PicksWindow } from './PicksWindow'
import styles from './PicksDialogs.module.css'

type Props = {
  /** The tapped book with an e-book in my libraries (`bookId` set); null keeps the window closed. */
  book: PickBook | null
  plan: PlanParams
  onClose: () => void
  /** [책 검색 >>]: the page keeps its place, then opens the book screen (S3). */
  onSearch: (bookId: number) => void
  phone: boolean
  /** When the book was recommended ("2025년 KAIST 사서추천"), KAIST only (user 2026-10-20); else nothing. */
  yearLine?: string | null
}

/**
 * S14 (R6-1~R6-8): the tapped cover, our e-book's title, the recommendation line "2026년 10월 사서추천 · 문학" (cycle
 * 14 [확정]), author and publisher (R6-2), [책 검색 >>] and at most three
 * library lines: only the first three plan holdings are asked (`holdingLimit=3`, user 2026-10-12), then sorted by
 * `pickLibraryRows`. The lines are S3's (`ResultRow`): a tap opens the library's book page in a new tab.
 */
export function PicksBookDialog({ book, plan, onClose, onSearch, phone, yearLine = null }: Props) {
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
          bookId={book.bookId}
          yearLine={yearLine}
          plan={plan}
          onSearch={onSearch}
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
  bookId,
  yearLine,
  plan,
  onSearch,
  phone,
}: {
  book: PickBook
  bookId: number
  yearLine: string | null
  plan: PlanParams
  onSearch: (bookId: number) => void
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


  const head = (
    <div className={styles.bookHead}>
      <CoverImage className={styles.bookCover} url={book.coverUrl} title={book.title} />
      <div className={styles.bookText}>
        {/* the shown name (cycle 21 R6-3): our title without edition marks. No "목록 이름" line (user 2026-10-17:
            "목록 이름 아예 다 빼줘 무의미한것 같아"; `listTitle` still comes from the API) */}
        <h2 className={styles.bookTitle}>{book.title}</h2>
        {yearLine && <p className={styles.bookYear}>{yearLine}</p>}
        {detail && (
          <p className={styles.bookMeta}>
            {[detail.authorDisplay, detail.publisher].filter(Boolean).join(' · ') || '저자·출판사 정보 없음'}
          </p>
        )}
        {/* [영상 보기] (R6-2): next to "책 검색 >>" on PC and tablets, at the right end of the same line on phones */}
        <div
          className={styles.bookActions}
          data-phone={phone || undefined}
         
        >
          {!error && (
            <button type="button" className={styles.searchButton} onClick={() => onSearch(bookId)}>
              책 검색
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
      {book.aiSummary && <AiSummary text={book.aiSummary} />}
      {/* no line naming the rows (user 2026-10-20: "추천 도서에서 "검색하는 도서관 중 앞의 3곳" 빼줘") */}
      <div className={styles.rows}>
        {rows.map((row) => (
          <ResultRow
            key={`${row.libraryId}-${row.rank}`}
            row={row}
            library={libraries.get(row.libraryId)}
            searchUrl={undefined}
            screen="picks"
            // the join-condition pills as on the results (user 2026-10-20: "추천도서 눌렀을 때도 가입조건 태그 다 보였으면")
            tags
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

/** What the ⓘ next to 「AI 요약」 says (user 2026-10-19: "짧게 ai가 요약한거라고 … i를 누른다던가"; no model name). */
export const AI_SUMMARY_NOTE = 'AI가 책 정보를 바탕으로 쓴 짧은 요약이에요. 실제 내용과 다를 수 있어요.'

/**
 * 안 B (user 2026-10-19): a small violet 「AI 요약 ⓘ」 label, then the summary in darker words than the grey line under
 * it, above the library rows. The ⓘ opens a short note.
 */
function AiSummary({ text }: { text: string }) {
  return (
    <div className={styles.aiSummary}>
      <Popover.Root>
        <Popover.Trigger className={styles.aiLabel} aria-label="AI 요약 안내">
          AI 요약
          <span className={styles.aiInfo} aria-hidden="true">
            i
          </span>
        </Popover.Trigger>
        <Popover.Portal>
          <Popover.Content className={styles.aiNote} side="top" align="start" sideOffset={6} collisionPadding={12}>
            {AI_SUMMARY_NOTE}
          </Popover.Content>
        </Popover.Portal>
      </Popover.Root>
      <p className={styles.aiText}>{text}</p>
    </div>
  )
}
