import { Fragment, useEffect, useState, type ReactNode } from 'react'
import { Link, useNavigate, useParams, useSearchParams } from 'react-router'
import { ApiError, fetchBook } from '../../api/client'
import { AiSummary } from '../picks/AiSummary'
import { CoverImage } from '../picks/CoverImage'
import { useBookVisit } from '../picks/useBookVisit'
import type { BookDetail, ResultFilter, VisitFilter } from '../../api/types'
import { readFilter, writeFilter } from '../search/resultFilter'
import { useRegions } from '../region/RegionContext'
import { readScope } from '../search/readScope'
import { loadPrefs, savePrefs, writeVisit } from '../search/searchSettings'
import { holdingBatchViews } from '../search/holdingView'
import { BUSY_BOOK_TEXT, BusyRetryNotice } from '../search/BusyRetryNotice'
import { ResultList, type ResultSearchContext } from '../search/ResultList'
import { useMineLabel, useTagPlace } from '../search/regionPlace'
import { useHoldings } from '../search/useHoldings'
import { useConditionCard, type ConditionPlan } from '../search/useConditionCard'
import styles from './BookPage.module.css'
import { ChevronLeft } from '../../components/icons'

/**
 * S3 book (flow.md): one book from 책 먼저 고르기 and the plan libraries holding it, asked on their detail pages
 * (same stream as 바로 보기 with one book). Address `/books/{id}` + filter, scope, visit [안]; regions come from this
 * browser.
 */
export function BookPage() {
  const { bookId = '' } = useParams()
  const [params] = useSearchParams()
  const { ready } = useRegions()
  const scope = readScope(params)
  // the condition card as on the search screen (user 2026-10-20: "이 창에서도 지역 조건 수정할 수 있으면 좋겠어")
  // a changed condition waits for [다시 찾기] under the card (user 2026-10-20)
  const { condition, plan, visit } = useConditionCard()
  // a new book, filter, plan, region set or added libraries start over from a fresh page state
  const key = `${bookId}?${params}|${plan.residence.join(',')}|${plan.work.join(',')}|${plan.libraryIds.join(',')}|${scope}|${visit}|${ready}`
  return <BookView key={key} bookId={bookId} plan={plan} visit={visit} condition={condition} />
}

function BookView({
  bookId,
  plan: regions,
  visit,
  condition,
}: {
  bookId: string
  plan: ConditionPlan
  visit: VisitFilter
  condition: ReactNode
}) {
  const [params, setParams] = useSearchParams()
  const navigate = useNavigate()
  // what the address leaves out comes from this browser's last choice (user request 2026-10-05)
  const [prefs] = useState(() => loadPrefs())
  const filter = readFilter(params, prefs.filter)
  const scope = readScope(params)
  const { ready } = useRegions()
  const libraryIds = regions.libraryIds
  const residenceKey = regions.residence.join(',')
  const workKey = regions.work.join(',')
  const libraryKey = libraryIds.join(',')
  const mineLabel = useMineLabel(regions.residence, regions.work)
  // the place words of the [{강서구} 거주 가입] tags (handoff 5-A-5)
  const tagPlace = useTagPlace(
    residenceKey && workKey ? [...regions.residence, ...regions.work] : residenceKey ? regions.residence : regions.work,
  )

  const holdings = useHoldings()
  const { open } = holdings
  const [book, setBook] = useState<BookDetail | null>(null)
  const [error, setError] = useState<ApiError | null>(null)
  // 「나에게 맞는 책」 (cycle 23 R5-3): opening the book screen and staying 10 seconds count in this browser
  useBookVisit(book?.id, book?.title)

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
      <ChevronLeft size={7} /> 목록으로
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
              {/* [확정] (cycle 08 R7, ADR-0070): the book may have left at an import of the libraries' lists */}
              <p className={styles.emptyNote}>도서관 목록을 새로 받으면서 빠진 책일 수 있어요. 다시 검색해 주세요.</p>
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

  const loadingBatch = holdings.state.batches.at(-1)
  // cycle 28 (ADR-0173): the first stream was refused with 503 and nothing came back (also while it is opened again
  // after 「대출 정보 다시 확인」): the notice with the button in the list's place, so "이 책을 가진 도서관을 모두
  // 조회했습니다" is not shown [확정]
  const rechecking = holdings.openRefused && holdings.state.phase === 'streaming'
  const busyOnly =
    holdings.openRefused &&
    holdings.state.order.length === 0 &&
    (rechecking || (holdings.state.phase !== 'streaming' && holdings.state.blocked === 'busy'))
  // the same groups' small texts and buttons as on 바로 보기 (user 2026-10-19), for this book's own address
  const searchAgain = (change: (address: URLSearchParams) => void) => {
    const address = new URLSearchParams(params)
    change(address)
    setParams(address)
  }
  const searchContext: ResultSearchContext = {
    tagPlace,
    hasMine: residenceKey !== '' || workKey !== '' || libraryKey !== '',
    mineLabel,
    noMineNote: true,
    regionBasis: residenceKey && workKey ? 'both' : workKey ? 'work' : residenceKey ? 'residence' : 'both',
    scope,
    visit,
    onSearchAll: () => searchAgain((address) => address.set('scope', 'all')),
    onVisitNone: () => {
      savePrefs({ visit: 'none' })
      searchAgain((address) => writeVisit('none', address))
    },
    onVisitBack: () => {
      savePrefs({ visit: 'any' })
      searchAgain((address) => writeVisit('any', address))
    },
  }
  const changeFilter = (next: ResultFilter) => {
    savePrefs({ filter: next })
    setParams(writeFilter(next, new URLSearchParams(params)), { replace: true })
  }

  return (
    <div className={styles.page}>
      {back}
      <header className={styles.book}>
        {/* cycle 26 (flow S2): the edition's cover on the left; without one the words start at the left edge */}
        <div className={styles.head}>
          {book.coverUrl && <CoverImage className={styles.cover} url={book.coverUrl} title={book.title} />}
          <div className={styles.headText}>
            <h1 className={styles.title}>{book.title}</h1>
            <p className={styles.meta}>
              {/* each part whole: beside the cover a date broke as "2021-" / "05-06" (phone check 2026-10-29) */}
              {metaParts(book).map((part, index, parts) => (
                <Fragment key={index}>
                  {index > 0 && ' '}
                  <span className={styles.metaPart}>{index < parts.length - 1 ? `${part} ·` : part}</span>
                </Fragment>
              ))}
              {metaParts(book).length === 0 && '저자·출판사 정보 없음'}
            </p>
            {/* no collection dates (user 2026-10-17: "굳이 언제부터 언제까지 수집한거다 알려줄 필요 없다고 생각해") */}
            <p className={styles.collected}>보유 도서관 {book.holdingLibraryCount}곳</p>
          </div>
        </div>
        {/* the whole 책첵 summary, not cut; no box without one (cycle 26 AC-2, AC-3) */}
        {book.summary && <AiSummary className={styles.summary} text={book.summary} />}
      </header>
      <div className={styles.condition}>{condition}</div>
      {book.holdingLibraryCount === 0 ? (
        <p className={styles.loading}>조건에 맞는 도서관이 없어요</p>
      ) : busyOnly ? (
        <BusyRetryNotice text={BUSY_BOOK_TEXT} checking={rechecking} onRetry={holdings.reopen} />
      ) : (
        <ResultList
          screen="book_page"
          // the groups and pills as on 바로 보기 (user 2026-10-19: "책 먼저 고르기 … 바로 보기일 때 보이는 걸로 맞춰줘",
          // "방문 가입이 안 보여"; cycle 22 D9 left them out)
          grouped
          tags
          searchContext={searchContext}
          batches={holdingBatchViews(holdings.state, filter)}
          filter={filter}
          onFilterChange={changeFilter}
          libraries={holdings.state.libraries}
          searchUrls={new Map()}
          remaining={holdings.remaining}
          loading={holdings.state.phase === 'streaming'}
          loadingLibraries={loadingBatch ? new Set(loadingBatch.requestedLibraries).size : 0}
          batchFailed={holdings.state.batchFailed}
          blocked={holdings.state.blocked}
          onMore={() => void holdings.loadNext()}
          moreText={`(남은 도서관 ${holdings.remaining}곳)`}
          finishedText="이 책을 가진 도서관을 모두 조회했습니다"
        />
      )}
    </div>
  )
}

/** The author, publisher and date shown, the empty ones left out. */
function metaParts(book: BookDetail): string[] {
  return [book.authorDisplay, book.publisher, book.publishedOn].filter((part): part is string => Boolean(part))
}
