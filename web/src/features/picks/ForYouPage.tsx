import { useEffect, useMemo, useRef, useState } from 'react'
import { Link, useNavigate } from 'react-router'
import { ApiError, fetchForYouBooks, fetchForYouSimilar } from '../../api/client'
import { sendUsageEvent } from '../../api/usageEvents'
import type { ForYouBooks, ForYouCard as Card, ForYouSimilar } from '../../api/types'
import { onWindowEntry } from '../../lib/useBackToClose'
import { useMediaQuery } from '../../lib/useMediaQuery'
import { useRegions } from '../region/RegionContext'
import { useConditionCard } from '../search/useConditionCard'
import { BookCard } from './BookCard'
import { cardsFor, chipsFor } from './forYouChips'
import { pickBook, yearLineOf } from './forYouBook'
import { layOut, MAX_PERSONAL, PAGE_SIZE, withSurprises } from './forYouOrder'
import { FOR_YOU_TEXT } from './forYouText'
import { MORE_STEP } from './picksText'
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
    viewed,
  }
}

/**
 * S18 「나에게 맞는 책」 (`/picks/for-you`, cycle 23; no AI line since 2026-10-25, user "빼줘"): the base books' line when this browser saw books,
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
  // the record is read when the screen opens (S19 is on 마이페이지 since 2026-10-25); books opened here change it for later
  const [record] = useState(readRecord)
  const baseIds = record.base.map((book) => book.bookId)
  const similarKey = `${key}|${baseIds.join(',')}|${record.reshow.join(',')}`
  const [baseTry, setBaseTry] = useState(0)
  const [similarTry, setSimilarTry] = useState(0)
  const [baseLoad, setBaseLoad] = useState<BaseLoad | null>(null)
  const [similarLoad, setSimilarLoad] = useState<SimilarLoad | null>(null)
  const [chip, setChip] = useState('all')
  const [shown, setShown] = useState(MORE_STEP)
  const [selected, setSelected] = useState<Card | null>(null)

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
          if (controller.signal.aborted) return
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
    MAX_PERSONAL / PAGE_SIZE,
  )
  // one list, as on the list tabs: the personal picks (all of them) first, then the base list
  // now and then a book of a field this browser never looked at (user 2026-10-25)
  const lookedGroups = new Set(record.viewed.flatMap((book) => (book.fieldGroup ? [book.fieldGroup] : [])))
  const all = withSurprises(
    layout.top ? [layout.top, ...layout.personal, ...layout.base] : [],
    lookedGroups,
    record.seen.size,
  )
  const personalIds = useMemo(() => {
    const ids = new Set<number>()
    for (const card of similarResult?.data?.books ?? []) ids.add(card.bookId)
    for (const card of similarResult?.data?.reshowBooks ?? []) ids.add(card.bookId)
    return ids
  }, [similarResult])
  const chips = chipsFor(all, personalIds, record.viewed)
  const currentChip = chips.some((c) => c.code === chip) ? chip : 'all'

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

  let body
  if (!baseResult || (similarWaiting && !baseResult.failed)) {
    body = <LoadingCards />
  } else if (baseResult.failed) {
    body = (
      <p className={picksStyles.notice} role="alert">
        {FOR_YOU_TEXT.baseFailed}{' '}
        <button type="button" className={styles.retry} onClick={() => {
            setBaseLoad(null)
            setBaseTry((n) => n + 1)
          }}>
          {FOR_YOU_TEXT.retry}
        </button>
      </p>
    )
  } else if (all.length === 0) {
    body = (
      <div className={picksStyles.empty}>
        <p>{FOR_YOU_TEXT.noneInMyLibraries}</p>
        <Link to="/mypage" className={styles.myPageLink}>
          {FOR_YOU_TEXT.toMyPage}
        </Link>
      </div>
    )
  } else {
    const inChip = cardsFor(currentChip, all, personalIds)
    const books = inChip.slice(0, shown)
    const left = inChip.length - books.length
    body = (
      <>
        <div className={picksStyles.chips} role="group" aria-label="분류">
          {chips.map((chip) => (
            <button
              key={chip.code}
              type="button"
              className={picksStyles.chip}
              aria-pressed={chip.code === currentChip}
              onClick={() => {
                setChip(chip.code)
                setShown(MORE_STEP)
              }}
            >
              {chip.name}
            </button>
          ))}
        </div>
        <section className={picksStyles.results} aria-label="책첵 추천 목록">
          <ol className={picksStyles.grid}>
            {books.map((card) => (
              <BookCard key={card.bookId} book={pickBook(card)} onPick={() => pick(card)} />
            ))}
          </ol>
          {left > 0 && (
            <button type="button" className={picksStyles.more} onClick={() => setShown((n) => n + MORE_STEP)}>
              더 보기 <span className={picksStyles.moreCount}>({Math.min(left, MORE_STEP)}권)</span>
            </button>
          )}
        </section>
      </>
    )
  }

  return (
    <div className={picksStyles.page}>
      <h1 className="visually-hidden">책첵 추천</h1>
      <PicksTabs current="for-you" />
      <div className={picksStyles.condition}>{condition}</div>
      {record.base.length > 0 && similarResult?.failed && (
        <p className={styles.baseLine} role="alert">
          {FOR_YOU_TEXT.similarFailed}{' '}
          <button type="button" className={styles.retry} onClick={() => {
            setSimilarLoad(null)
            setSimilarTry((n) => n + 1)
          }}>
            {FOR_YOU_TEXT.retry}
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
        yearLine={selected ? yearLineOf(selected) : null}
      />
    </div>
  )
}

/** Loading: the chips' place, then the grid of cards as on the list tabs. */
function LoadingCards() {
  return (
    <section className={picksStyles.results} aria-busy="true">
      <p className="visually-hidden" role="status">
        {FOR_YOU_TEXT.loading}
      </p>
      <ol className={picksStyles.grid} aria-hidden="true">
        {[0, 1, 2, 3, 4, 5, 6, 7].map((index) => (
          <li key={index} className={picksStyles.cell}>
            <span className={picksStyles.skeleton} data-testid="skeleton-card" />
          </li>
        ))}
      </ol>
    </section>
  )
}
