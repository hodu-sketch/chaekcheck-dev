import { useEffect, useLayoutEffect, useRef, useState } from 'react'
import { useNavigate } from 'react-router'
import { ApiError, fetchPopularBooks } from '../../api/client'
import type { PopularBook, PopularBooks } from '../../api/types'
import { onWindowEntry } from '../../lib/useBackToClose'
import { useMediaQuery } from '../../lib/useMediaQuery'
import { useRegions } from '../region/RegionContext'
import { CategoryPicker } from './CategoryPicker'
import { CategorySection } from './CategorySection'
import { PAGE_SIZE, monthLabel, sectionId, sourceLine } from './popularText'
import { PopularBookDialog } from './PopularBookDialog'
import { PopularHelp } from './PopularHelp'
import { readPosition, savePosition } from './popularPosition'
import { RequestNotice } from './RequestNotice'
import { usePopularPlan } from './usePopularPlan'
import styles from './PopularPage.module.css'

/** [안] (cycle 10 step 7-6): the phone layout and bottom sheets, the same width as the other screens' phone layout. */
export const PHONE_QUERY = '(width <= 640px)'

type Loaded = { key: string; data: PopularBooks | null; failed: boolean }

/**
 * S13 popular (flow.md, R4·R5·R7): last month's sales ranking by category, each category a block as tall as the
 * screen with ◀ 3 x 3 ▶; scrolling down brings the next category, and the category box at the top follows the block
 * in the middle of the screen (AC-3). Covers of books with no e-book in my libraries are faded (AC-6). The pages and
 * the scroll position stay in this history entry, so back from the book screen comes to the same place (AC-20).
 */
export function PopularPage() {
  const navigate = useNavigate()
  const phone = useMediaQuery(PHONE_QUERY)
  const { plan, ready, key } = usePopularPlan()
  const { resolveStoredRegions } = useRegions()
  const [loaded, setLoaded] = useState<Loaded | null>(null)
  const [pages, setPages] = useState<Record<string, number>>(() => readPosition()?.pages ?? {})
  const [restoreY] = useState(() => readPosition()?.scrollY ?? null)
  const restored = useRef(false)
  const [current, setCurrent] = useState('')
  const [selected, setSelected] = useState<PopularBook | null>(null)
  const rootRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    if (!ready) return
    const controller = new AbortController()
    let retried = false
    const load = async () => {
      try {
        const data = await fetchPopularBooks(plan, controller.signal)
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
  }, [ready, plan, key, resolveStoredRegions])

  // the place stays in this history entry (AC-20); the browser does not move the page on back and forth
  useEffect(() => {
    savePosition({ pages })
  }, [pages])
  useEffect(() => {
    let timer: number | undefined
    const onScroll = () => {
      window.clearTimeout(timer)
      timer = window.setTimeout(() => savePosition({ scrollY: window.scrollY }), 150)
    }
    window.addEventListener('scroll', onScroll, { passive: true })
    const before = window.history.scrollRestoration
    window.history.scrollRestoration = 'manual'
    return () => {
      window.clearTimeout(timer)
      window.removeEventListener('scroll', onScroll)
      window.history.scrollRestoration = before
    }
  }, [])

  // the sticky category bar sits right under the app header, whatever its height on this width
  useEffect(() => {
    const header = document.querySelector<HTMLElement>('[data-app-header]')
    const root = rootRef.current
    if (!header || !root) return
    const apply = () => root.style.setProperty('--app-header', `${header.offsetHeight}px`)
    apply()
    if (typeof ResizeObserver === 'undefined') return
    const observer = new ResizeObserver(apply)
    observer.observe(header)
    return () => observer.disconnect()
  }, [])

  const result = loaded?.key === key ? loaded : null
  const data = result?.data ?? null
  const categories = data?.month ? data.categories : []
  const codes = categories.map((c) => c.code).join(',')

  useLayoutEffect(() => {
    if (restored.current || !codes) return
    restored.current = true
    if (restoreY) window.scrollTo(0, restoreY)
  }, [codes, restoreY])

  // the category box follows the block in the middle of the screen (R4-2, AC-3) [안: IntersectionObserver]
  useEffect(() => {
    if (!codes) return
    const list = codes.split(',')
    const observer =
      typeof IntersectionObserver === 'undefined'
        ? null
        : new IntersectionObserver(
            (entries) => {
              for (const entry of entries) {
                const code = (entry.target as HTMLElement).dataset.code
                if (entry.isIntersecting && code) setCurrent(code)
              }
            },
            { rootMargin: '-50% 0px -50% 0px' },
          )
    for (const code of list) {
      const section = document.getElementById(sectionId(code))
      if (section) observer?.observe(section)
    }
    // at the very bottom only the source line and the footer cross the middle: the last category then (step 8, 320px)
    const atBottom = () => {
      const max = document.documentElement.scrollHeight - window.innerHeight
      if (max > 0 && window.scrollY >= max - 2) setCurrent(list[list.length - 1])
    }
    window.addEventListener('scroll', atBottom, { passive: true })
    return () => {
      observer?.disconnect()
      window.removeEventListener('scroll', atBottom)
    }
  }, [codes])

  const currentCode = categories.some((c) => c.code === current) ? current : (categories[0]?.code ?? '')

  // S13c: that category's ranks 1-9, the page put straight there with no smooth scrolling (user 2026-10-11)
  const jump = (code: string) => {
    setPages((before) => ({ ...before, [code]: 0 }))
    setCurrent(code)
    requestAnimationFrame(() => {
      document.getElementById(sectionId(code))?.scrollIntoView?.({ block: 'start', behavior: 'instant' })
    })
  }
  const pick = (book: PopularBook) => {
    savePosition({ scrollY: window.scrollY })
    setSelected(book)
  }
  // [책 검색 >>] (AC-9, AC-20): from a phone window's own history entry the book screen replaces that entry, so back
  // comes to this screen, not to the closed window
  const openBook = (bookId: number) => {
    savePosition({ scrollY: window.scrollY })
    navigate(`/books/${bookId}`, { replace: onWindowEntry() })
  }

  const month = monthLabel(data?.month ?? null)
  let body
  if (!result) {
    body = <LoadingBlock />
  } else if (result.failed) {
    body = (
      <p className={styles.notice} role="alert">
        인기 도서를 불러오지 못했어요. 잠시 뒤에 다시 해 주세요.
      </p>
    )
  } else if (categories.length === 0) {
    body = <p className={styles.empty}>아직 인기 도서를 준비하고 있어요</p>
  } else {
    body = (
      <>
        {categories.map((category) => (
          <CategorySection
            key={category.code}
            category={category}
            page={pages[category.code] ?? 0}
            onPage={(page) => setPages((before) => ({ ...before, [category.code]: page }))}
            onPick={pick}
          />
        ))}
        <p className={styles.source}>{sourceLine(data?.month ?? null, data?.fetchedAt ?? null)}</p>
      </>
    )
  }

  return (
    <div ref={rootRef} className={styles.page}>
      {/* design A (user 2026-10-12): the title left and [전체 ▾] ⓘ right in a solid bar above the panels */}
      <div className={styles.bar}>
        <div className={styles.intro}>
          {month && (
            <p className={styles.eyebrow}>
              <span className={styles.nowrap}>{month}</span> <span className={styles.nowrap}>판매 순위</span>
            </p>
          )}
          <h1 className={styles.heading}>인기 도서</h1>
        </div>
        {categories.length > 0 && (
          <div className={styles.tools}>
            <CategoryPicker categories={categories} current={currentCode} onPick={jump} phone={phone} />
            <PopularHelp month={data?.month ?? null} fetchedAt={data?.fetchedAt ?? null} phone={phone} />
          </div>
        )}
      </div>
      <div className={styles.blocks}>{body}</div>
      <PopularBookDialog
        book={selected?.available && selected.bookId != null ? selected : null}
        plan={plan}
        onClose={() => setSelected(null)}
        onSearch={openBook}
        phone={phone}
      />
      <RequestNotice
        book={selected && !(selected.available && selected.bookId != null) ? selected : null}
        onClose={() => setSelected(null)}
        phone={phone}
      />
    </div>
  )
}

/** Loading (flow.md S13): nine gray cards where the first category will be. */
function LoadingBlock() {
  return (
    <section className={styles.section} aria-busy="true">
      <p className="visually-hidden" role="status">
        인기 도서를 불러오는 중
      </p>
      <div className={styles.panel}>
        <div className={styles.pager}>
          <span className={styles.arrowSpace} data-side="prev" />
          <ol className={styles.grid} aria-hidden="true">
            {Array.from({ length: PAGE_SIZE }, (_, index) => (
              <li key={index} className={styles.cell}>
                <span className={styles.skeleton} data-testid="skeleton-card" />
              </li>
            ))}
          </ol>
          <span className={styles.arrowSpace} data-side="next" />
        </div>
      </div>
    </section>
  )
}
