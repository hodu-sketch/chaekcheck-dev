import { useEffect, useMemo, useRef, useState } from 'react'
import { Link, useNavigate } from 'react-router'
import { ApiError, fetchForYouBooks, fetchForYouSimilar } from '../../api/client'
import { sendUsageEvent } from '../../api/usageEvents'
import type { ForYouBooks, ForYouCard as Card, ForYouSimilar, PickBook } from '../../api/types'
import { onWindowEntry } from '../../lib/useBackToClose'
import { useMediaQuery } from '../../lib/useMediaQuery'
import { useRegions } from '../region/RegionContext'
import { useConditionCard } from '../search/useConditionCard'
import { BaseBooksDialog } from './BaseBooksDialog'
import { ForYouCard } from './ForYouCard'
import { layOut } from './forYouOrder'
import { baseLine, FOR_YOU_TEXT } from './forYouText'
import { PHONE_QUERY } from './PicksPage'
import { PicksBookDialog } from './PicksBookDialog'
import { PicksTabs } from './PicksTabs'
import { usePicksPlan } from './usePicksPlan'
import { baseBooks, readViewed, reshowBooks } from './viewedBooks'
import picksStyles from './PicksPage.module.css'
import styles from './ForYouPage.module.css'

type BaseLoad = { key: string; data: ForYouBooks | null; failed: boolean }
type SimilarLoad = { key: string; data: ForYouSimilar | null; failed: boolean }

/** The base books, the books to show again and every seen book, from this browser's record. */
function readRecord() {
  const now = Date.now()
  const viewed = readViewed(now)
  return {
    base: baseBooks(viewed, now),
    reshow: reshowBooks(viewed, now).map((book) => book.bookId),
    seen: new Set(viewed.map((book) => book.bookId)),
  }
}

/** S14 takes a picks book; a for-you card is one with its plan library known to hold it. */
function pickBook(card: Card): PickBook {
  return {
    id: String(card.bookId),
    category: 'for_you',
    title: card.title,
    listTitle: null,
    coverUrl: card.coverUrl,
    bookId: card.bookId,
    available: true,
    recommendedMonth: null,
    yearBook: null,
    videoId: null,
    tocNo: null,
  }
}

/**
 * S18 「나에게 맞는 책」 (`/picks/for-you`, cycle 23): the AI line, the base books' line when this browser saw books,
 * one wide card, then two cards a row: the personal picks (20 at a time, "비슷한 책 더 보기"), then the base list.
 * The base list and the personal picks are asked at the same time; the personal picks only when there are base books
 * (AC-2), and only the base books and the books to show again leave the browser (R5-1).
 */
export function ForYouPage() {
  const navigate = useNavigate()
  const phone = useMediaQuery(PHONE_QUERY)
  const { condition, plan: asked, visit: askedVisit } = useConditionCard()
  const { plan, ready, key } = usePicksPlan(asked, askedVisit)
  const { resolveStoredRegions } = useRegions()
  // the record is read when the screen opens and again after S19; books opened from here change it only for later
  const [record, setRecord] = useState(readRecord)
  const baseIds = record.base.map((book) => book.bookId)
  const similarKey = `${key}|${baseIds.join(',')}|${record.reshow.join(',')}`
  const [baseTry, setBaseTry] = useState(0)
  const [similarTry, setSimilarTry] = useState(0)
  const [baseLoad, setBaseLoad] = useState<BaseLoad | null>(null)
  const [similarLoad, setSimilarLoad] = useState<SimilarLoad | null>(null)
  const [pages, setPages] = useState(1)
  const [selected, setSelected] = useState<Card | null>(null)
  const [changing, setChanging] = useState(false)

  useEffect(() => {
    if (!ready) return
    const controller = new AbortController()
    let retried = false
    const load = async () => {
      try {
        const data = await fetchForYouBooks(plan, controller.signal)
        setBaseLoad({ key, data, failed: false })
      } catch (failure) {
        if (controller.signal.aborted) return
        // saved region ids that were merged or split: check them once, then ask again with the new ids
        if (failure instanceof ApiError && failure.problem.code === 'invalid_region' && !retried) {
          retried = true
          if (await resolveStoredRegions().catch(() => false)) return
        }
        setBaseLoad({ key, data: null, failed: true })
      }
    }
    void load()
    return () => controller.abort()
  }, [ready, plan, key, resolveStoredRegions, baseTry])

  const baseIdsKey = baseIds.join(',')
  const reshowKey = record.reshow.join(',')
  useEffect(() => {
    if (!ready || !baseIdsKey) return
    const controller = new AbortController()
    fetchForYouSimilar(baseIdsKey.split(',').map(Number), reshowKey ? reshowKey.split(',').map(Number) : [], plan,
      controller.signal)
      .then((data) => setSimilarLoad({ key: similarKey, data, failed: false }))
      .catch(() => {
        if (!controller.signal.aborted) setSimilarLoad({ key: similarKey, data: null, failed: true })
      })
    return () => controller.abort()
  }, [ready, plan, baseIdsKey, reshowKey, similarKey, similarTry])

  const baseResult = baseLoad?.key === key ? baseLoad : null
  const similarResult = baseIds.length > 0 && similarLoad?.key === similarKey ? similarLoad : null
  const similarWaiting = baseIds.length > 0 && similarResult === null
  const layout = layOut(
    baseResult?.data?.books ?? [],
    similarResult?.data?.books ?? [],
    similarResult?.data?.reshowBooks ?? [],
    record.seen,
    pages,
  )
  const personalIds = useMemo(() => {
    const ids = new Set<number>()
    for (const card of similarResult?.data?.books ?? []) ids.add(card.bookId)
    for (const card of similarResult?.data?.reshowBooks ?? []) ids.add(card.bookId)
    return ids
  }, [similarResult])

  // one usage report when the tab's list is shown (R11-1, category "all")
  const reported = useRef(false)
  useEffect(() => {
    if (reported.current || !baseResult?.data) return
    reported.current = true
    sendUsageEvent({ type: 'picks_list_view', list: 'for_you', category: 'all' })
  }, [baseResult])

  const pick = (card: Card) => {
    setSelected(card)
    sendUsageEvent({
      type: 'picks_book_view',
      list: 'for_you',
      category: personalIds.has(card.bookId) ? 'similar' : 'base',
      bookId: card.bookId,
    })
  }
  const openBook = (bookId: number) => navigate(`/books/${bookId}`, { replace: onWindowEntry() })
  const closeDialog = () => {
    setChanging(false)
    setPages(1)
    setRecord(readRecord())
  }

  let body
  if (!baseResult || (similarWaiting && !baseResult.failed)) {
    body = <LoadingCards />
  } else if (baseResult.failed) {
    body = (
      <p className={picksStyles.notice} role="alert">
        {FOR_YOU_TEXT.baseFailed}{' '}
        <button type="button" className={styles.retry} onClick={() => setBaseTry((n) => n + 1)}>
          {FOR_YOU_TEXT.retry}
        </button>
      </p>
    )
  } else if (layout.top === null) {
    body = (
      <div className={picksStyles.empty}>
        <p>{FOR_YOU_TEXT.noneInMyLibraries}</p>
        <Link to="/mypage" className={styles.myPageLink}>
          {FOR_YOU_TEXT.toMyPage}
        </Link>
      </div>
    )
  } else {
    body = (
      <section className={styles.results} aria-label="나에게 맞는 책 목록">
        <ol className={styles.grid}>
          <ForYouCard card={layout.top} wide onPick={pick} />
          {layout.personal.map((card) => (
            <ForYouCard key={`p-${card.bookId}`} card={card} onPick={pick} />
          ))}
        </ol>
        {layout.hasMore && (
          <button type="button" className={picksStyles.more} onClick={() => setPages((n) => n + 1)}>
            {FOR_YOU_TEXT.more}
          </button>
        )}
        {layout.base.length > 0 && (
          <ol className={`${styles.grid} ${styles.baseGrid}`}>
            {layout.base.map((card) => (
              <ForYouCard key={`b-${card.bookId}`} card={card} onPick={pick} />
            ))}
          </ol>
        )}
      </section>
    )
  }

  const baseTitles = record.base.map((book) => book.title).filter(Boolean)
  return (
    <div className={picksStyles.page}>
      <h1 className="visually-hidden">나에게 맞는 책</h1>
      <PicksTabs current="for-you" />
      <div className={picksStyles.condition}>{condition}</div>
      <p className={styles.aiLine}>{FOR_YOU_TEXT.aiLine}</p>
      {record.base.length > 0 && similarResult?.failed && (
        <p className={styles.baseLine} role="alert">
          {FOR_YOU_TEXT.similarFailed}{' '}
          <button type="button" className={styles.retry} onClick={() => setSimilarTry((n) => n + 1)}>
            {FOR_YOU_TEXT.retry}
          </button>
        </p>
      )}
      {record.base.length > 0 && !similarResult?.failed && (
        <p className={styles.baseLine}>
          <span className={styles.baseTitles}>{baseLine(baseTitles)}</span>
          <span className={styles.baseSep} aria-hidden="true">
            {' · '}
          </span>
          <button type="button" className={styles.changeBase} onClick={() => setChanging(true)}>
            {FOR_YOU_TEXT.changeBase}
          </button>
        </p>
      )}
      {body}
      <details className={picksStyles.criteria}>
        <summary className={picksStyles.criteriaSummary}>추천 기준과 출처 보기</summary>
        <ul className={picksStyles.helpList}>
          {FOR_YOU_TEXT.helpLines.map((line) => (
            <li key={line}>{line}</li>
          ))}
        </ul>
      </details>
      <PicksBookDialog
        book={selected ? pickBook(selected) : null}
        plan={plan}
        onClose={() => setSelected(null)}
        onSearch={openBook}
        phone={phone}
      />
      {changing && <BaseBooksDialog onClose={closeDialog} phone={phone} />}
    </div>
  )
}

/** Loading (flow S18): the wide card's place, then two cards a row. */
function LoadingCards() {
  return (
    <section className={styles.results} aria-busy="true">
      <p className="visually-hidden" role="status">
        {FOR_YOU_TEXT.loading}
      </p>
      <ol className={styles.grid} aria-hidden="true">
        <li className={styles.topCell}>
          <span className={`${picksStyles.skeleton} ${styles.topSkeleton}`} data-testid="skeleton-card" />
        </li>
        {[0, 1, 2, 3].map((index) => (
          <li key={index} className={styles.cell}>
            <span className={picksStyles.skeleton} data-testid="skeleton-card" />
          </li>
        ))}
      </ol>
    </section>
  )
}
