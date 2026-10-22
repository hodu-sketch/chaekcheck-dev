import { Fragment, useEffect, useLayoutEffect, useRef, useState } from 'react'
import { NavLink, useNavigate, useParams } from 'react-router'
import { ApiError, fetchPicks } from '../../api/client'
import { sendUsageEvent } from '../../api/usageEvents'
import type { PickBook, Picks } from '../../api/types'
import { onWindowEntry } from '../../lib/useBackToClose'
import { useMediaQuery } from '../../lib/useMediaQuery'
import { useRegions } from '../region/RegionContext'
import { BookCard, isLinked } from './BookCard'
import { EXAMPLE_PICK_NOTE, MORE_STEP } from './picksText'
import { PICKS_TABS, picksList, YOUTUBE_TERMS_URL, type PicksList } from './picksLists'
import { PicksBookDialog } from './PicksBookDialog'
import { readPosition, savePosition } from './picksPosition'
import { RequestNotice } from './RequestNotice'
import { usePicksPlan } from './usePicksPlan'
import { useConditionCard } from '../search/useConditionCard'
import { ExampleCardView, useExampleCards } from '../search/ExampleCard'
import { RegionPickButton } from '../search/LibraryScopeLine'
import styles from './PicksPage.module.css'

/** [안] (cycle 10 step 7-6): the phone layout and bottom sheets, the same width as the other screens' phone layout. */
export const PHONE_QUERY = '(width <= 640px)'

/** At most this many example cards between the books (user 2026-10-22: "이 강요 창 한 번만 나타나게"; three before). */
const EXAMPLE_MAX = 1

/** Rows between two example cards (user 2026-10-22: "나타나는 간격 지금보다 2배로", four before). */
const EXAMPLE_EVERY = 8

/**
 * Which example card follows the book at `index`, or null: the first after three rows, the next ones every eight rows
 * (user 2026-10-22: "스크롤을 좀 내린 다음에 나왔으면", the fourth row being out of the first window on a laptop).
 */
export function exampleSlot(index: number, columns: number): number | null {
  const row = (index + 1) / columns
  if (!Number.isInteger(row) || row < 3 || (row - 3) % EXAMPLE_EVERY !== 0) return null
  const slot = (row - 3) / EXAMPLE_EVERY
  return slot < EXAMPLE_MAX ? slot : null
}

/** [안] gray cards while loading: two rows of the PC grid. */
const SKELETON_CARDS = 8

type Loaded = { key: string; data: Picks | null; failed: boolean }

/**
 * `/picks/:listKey` (cycle 21, ADR-0126): one screen per list, made again when the list changes, so each keeps its own
 * place. Another name shows nothing, as any address the app does not know [안].
 */
export function PicksRoute() {
  const list = picksList(useParams().listKey)
  return list ? <PicksPage key={list.key} list={list} /> : null
}

/**
 * S13 picks (user 2026-10-19 redesign, from the user's reference picture): tabs for the three lists, a grey line, the
 * list's criteria and source in "추천 기준과 출처 보기", category chips, and a scrolling grid of book cards (PC 4
 * columns, tablet 3, phones 2) with "더 보기" adding 20 at a time. The chosen chip, the number shown and the scroll
 * position stay in this history entry, one place per list, so back from the book screen comes to the same place
 * (AC-20).
 */
export function PicksPage({ list }: { list: PicksList }) {
  // phones: the tab row swipes; the chosen list's tab is brought into view (user 2026-10-20)
  const tabsRef = useRef<HTMLElement>(null)
  useEffect(() => {
    const current = tabsRef.current?.querySelector<HTMLElement>('[aria-current="page"]')
    current?.scrollIntoView?.({ block: 'nearest', inline: 'nearest' })
  }, [list.key])
  const navigate = useNavigate()
  const phone = useMediaQuery(PHONE_QUERY)
  // the condition card under the list tabs (user 2026-10-20: "추천도서에도 지역설정 칸이 있었으면 해", picked B)
  // no [반영하기] (user 2026-10-20: "추천도서에서 지역 설정 반영하기 빼주라"); closing the window applies
  const { condition, plan: asked, visit: askedVisit } = useConditionCard()
  const { plan, ready, key } = usePicksPlan(asked, askedVisit)
  const { resolveStoredRegions, regions, libraryIds, ready: regionsReady } = useRegions()
  // the 「만약에 …에 살면」 cards between the books, only for someone with no region and no added library (user
  // 2026-10-22: "예시 카드는 추천도서에서 쓰일거잖아", "스크롤을 좀 내린 다음에"): after three rows, then every eight
  const tablet = useMediaQuery('(width <= 1024px)')
  const columns = phone ? 2 : tablet ? 3 : 4
  const noRegion =
    regionsReady && regions.residence.length === 0 && regions.work.length === 0 && libraryIds.length === 0
  const examples = useExampleCards(EXAMPLE_MAX)
  const [loaded, setLoaded] = useState<Loaded | null>(null)
  const [saved] = useState(() => readPosition(list.key))
  const [chosen, setChosen] = useState(saved?.category ?? '')
  const [shown, setShown] = useState(saved?.shown || MORE_STEP)
  const restored = useRef(false)
  const [selected, setSelected] = useState<PickBook | null>(null)

  useEffect(() => {
    if (!ready) return
    const controller = new AbortController()
    let retried = false
    const load = async () => {
      try {
        const data = await fetchPicks(list.key, plan, controller.signal)
        setLoaded({ key, data, failed: false })
      } catch (failure) {
        if (controller.signal.aborted) return
        // saved region ids that were merged or split: check them once, then ask again with the new ids
        if (failure instanceof ApiError && failure.problem.code === 'invalid_region' && !retried) {
          retried = true
          if (await resolveStoredRegions().catch(() => false)) return
        }
        setLoaded({ key, data: null, failed: true })
      }
    }
    void load()
    return () => controller.abort()
  }, [ready, plan, key, resolveStoredRegions, list.key])

  // the place stays in this history entry (AC-20); the browser does not move the page on back and forth
  useEffect(() => {
    savePosition(list.key, { category: chosen, shown })
  }, [chosen, shown, list.key])
  useEffect(() => {
    let timer: number | undefined
    const onScroll = () => {
      window.clearTimeout(timer)
      timer = window.setTimeout(() => savePosition(list.key, { scrollY: window.scrollY }), 150)
    }
    window.addEventListener('scroll', onScroll, { passive: true })
    const before = window.history.scrollRestoration
    window.history.scrollRestoration = 'manual'
    return () => {
      window.clearTimeout(timer)
      window.removeEventListener('scroll', onScroll)
      window.history.scrollRestoration = before
    }
  }, [list.key])

  const result = loaded?.key === key ? loaded : null
  const data = result?.data ?? null
  const categories = data?.categories ?? []
  const current = categories.find((c) => c.code === chosen) ?? categories[0] ?? null
  const currentCode = current?.code ?? ''

  useLayoutEffect(() => {
    if (restored.current || !currentCode) return
    restored.current = true
    if (saved?.scrollY) window.scrollTo(0, saved.scrollY)
  }, [currentCode, saved])

  // One usage report each time another category is shown, the first one included (cycle 15)
  const reportedCode = useRef('')
  useEffect(() => {
    if (!currentCode || reportedCode.current === currentCode) return
    reportedCode.current = currentCode
    sendUsageEvent({ type: 'picks_list_view', list: list.key, category: currentCode })
  }, [currentCode, list.key])

  const choose = (code: string) => {
    if (code === currentCode) return
    setChosen(code)
    setShown(MORE_STEP)
  }

  const pick = (book: PickBook) => {
    savePosition(list.key, { scrollY: window.scrollY })
    setSelected(book)
    // the book window opens only for a book with an e-book in my libraries (cycle 15 usage report)
    if (isLinked(book)) {
      sendUsageEvent({ type: 'picks_book_view', list: list.key, category: book.category, bookId: book.bookId! })
    }
  }
  // [책 검색 >>] (AC-9, AC-20): from a phone window's own history entry the book screen replaces that entry, so back
  // comes to this screen, not to the closed window
  const openBook = (bookId: number) => {
    savePosition(list.key, { scrollY: window.scrollY })
    navigate(`/books/${bookId}`, { replace: onWindowEntry() })
  }

  let body
  if (!result) {
    body = <LoadingGrid text={list.loadingText} />
  } else if (result.failed) {
    body = (
      <p className={styles.notice} role="alert">
        {list.failedText}
      </p>
    )
  } else if (!current) {
    body = <p className={styles.empty}>{list.emptyText}</p>
  } else {
    // books with an e-book first, then the rest, each in the list's order (user 2026-10-19: "전자책이 있는 걸 위에 없는 걸 아래로")
    const ordered = [...current.books.filter(isLinked), ...current.books.filter((book) => !isLinked(book))]
    const books = ordered.slice(0, shown)
    const left = ordered.length - books.length
    body = (
      <>
        <div className={styles.chips} role="group" aria-label="분류">
          {categories.map((category) => (
            <button
              key={category.code}
              type="button"
              className={styles.chip}
              aria-pressed={category.code === currentCode}
              onClick={() => choose(category.code)}
            >
              {category.name}
            </button>
          ))}
        </div>
        <section className={styles.results} aria-label={current.name}>
          <ol className={styles.grid} aria-label={`${current.name} ${current.books.length}권`}>
            {books.map((book, index) => {
              const slot = exampleSlot(index, columns)
              const example = noRegion && slot !== null ? examples[slot] : undefined
              return (
                <Fragment key={book.id}>
                  <BookCard book={book} onPick={pick} />
                  {example && (
                    <li className={styles.exampleSlot}>
                      <ExampleCardView phone={phone} card={example} className={styles.exampleCard} />
                      {/* the main page's region button and window (user 2026-10-22: "내 지역 정하기 이거 말고 그냥
                          메인의 지역을 선택해 주세요랑 똑같은 걸로") */}
                      <div className={styles.examplePick}>
                        <RegionPickButton phone={phone} />
                        <p className={styles.examplePickNote}>{EXAMPLE_PICK_NOTE}</p>
                      </div>
                    </li>
                  )}
                </Fragment>
              )
            })}
          </ol>
          {left > 0 && (
            <button type="button" className={styles.more} onClick={() => setShown((n) => n + MORE_STEP)}>
              더 보기 <span className={styles.moreCount}>({Math.min(left, MORE_STEP)}권)</span>
            </button>
          )}
        </section>
      </>
    )
  }

  return (
    <div className={styles.page}>
      <h1 className="visually-hidden">{list.name}</h1>
      <nav ref={tabsRef} className={styles.tabs} aria-label="추천도서 목록">
        {PICKS_TABS.map((tab) => (
          <NavLink key={tab.key} to={`/picks/${tab.key}`} className={styles.tab}>
            {/* the last two words stay together when a phone wraps the name ("KAIST / 사서 추천", user 2026-10-20) */}
            {tab.tabLabel.replace(/ (?=\S+$)/, ' ')}
          </NavLink>
        ))}
      </nav>
      <div className={styles.condition}>{condition}</div>
      {body}
      <details className={styles.criteria}>
        <summary className={styles.criteriaSummary}>추천 기준과 출처 보기</summary>
        <ul className={styles.helpList}>
          {list.helpLines.map((line) => (
            <li key={line}>{line}</li>
          ))}
          <li className={styles.source}>
            {list.source(data?.fetchedAt ?? null)}
            {list.sourceUrl && (
              <>
                {' · '}
                <a href={list.sourceUrl} target="_blank" rel="noopener noreferrer" className={styles.sourceLink}>
                  {list.sourceUrl}
                </a>
              </>
            )}
            {list.key === 'leedongjin' && (
              <>
                {' · '}
                <a href={YOUTUBE_TERMS_URL} target="_blank" rel="noopener noreferrer">
                  YouTube 서비스 약관
                </a>
              </>
            )}
          </li>
        </ul>
      </details>
      <PicksBookDialog
        book={selected && isLinked(selected) ? selected : null}
        plan={plan}
        onClose={() => setSelected(null)}
        onSearch={openBook}
        phone={phone}
        yearLine={selected ? (list.yearLine?.(selected) ?? null) : null}
      />
      <RequestNotice
        book={selected && !isLinked(selected) ? selected : null}
        onClose={() => setSelected(null)}
        phone={phone}
        yearLine={selected ? (list.yearLine?.(selected) ?? null) : null}
      />
    </div>
  )
}

/** Loading (flow.md S13): gray cards where the books will be. */
function LoadingGrid({ text }: { text: string }) {
  return (
    <section className={styles.results} aria-busy="true">
      <p className="visually-hidden" role="status">
        {text}
      </p>
      <ol className={styles.grid} aria-hidden="true">
        {Array.from({ length: SKELETON_CARDS }, (_, index) => (
          <li key={index} className={styles.cell}>
            <span className={styles.skeleton} data-testid="skeleton-card" />
          </li>
        ))}
      </ol>
    </section>
  )
}
