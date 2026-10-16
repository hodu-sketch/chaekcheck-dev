import { useEffect, useLayoutEffect, useRef, useState } from 'react'
import { useNavigate } from 'react-router'
import { ApiError, fetchPicks } from '../../api/client'
import { sendUsageEvent } from '../../api/usageEvents'
import type { PickBook, Picks } from '../../api/types'
import { onWindowEntry } from '../../lib/useBackToClose'
import { useMediaQuery } from '../../lib/useMediaQuery'
import { useRegions } from '../region/RegionContext'
import { CategoryPicker } from './CategoryPicker'
import { CategorySection } from './CategorySection'
import { PAGE_SIZE, sectionId, sourceLine } from './picksText'
import { PicksBookDialog } from './PicksBookDialog'
import { readPosition, savePosition } from './picksPosition'
import { RequestNotice } from './RequestNotice'
import { usePicksPlan } from './usePicksPlan'
import styles from './PicksPage.module.css'

/** [안] (cycle 10 step 7-6): the phone layout and bottom sheets, the same width as the other screens' phone layout. */
export const PHONE_QUERY = '(width <= 640px)'

/**
 * Touch screens: the main pointer is a finger. Only `pointer: coarse`, not with `hover: none`: Samsung Internet on a
 * phone reports `hover: hover` (checked on a real phone 2026-10-12), so the combination left touch mode off there.
 */
export const TOUCH_QUERY = '(pointer: coarse)'

/** [안] (cycle 10): how long the wheel must be still before the next wheel moves another category. */
const WHEEL_QUIET_MS = 350

/** [안] (cycle 10): how long one wheel or PageDown step glides. */
const GLIDE_MS = 350

/** [안] (cycle 10): how far a finger must move up or down (more than sideways) to move one category. */
const SWIPE_Y_PX = 30

type Loaded = { key: string; data: Picks | null; failed: boolean }

/**
 * S13 picks (cycle 14 flow.md, R4·R5·R7): the National Library of Korea's librarian picks of the latest six months by
 * field, each field a block as tall as the screen with ◀ 3 x 3 ▶; scrolling down brings the next category, and the category box at the top follows the block
 * in the middle of the screen (AC-3). Covers of books with no e-book in my libraries are faded (AC-6). The pages and
 * the scroll position stay in this history entry, so back from the book screen comes to the same place (AC-20).
 */
export function PicksPage() {
  const navigate = useNavigate()
  const phone = useMediaQuery(PHONE_QUERY)
  const touchMode = useMediaQuery(TOUCH_QUERY)
  const { plan, ready, key } = usePicksPlan()
  const { resolveStoredRegions } = useRegions()
  const [loaded, setLoaded] = useState<Loaded | null>(null)
  const [pages, setPages] = useState<Record<string, number>>(() => readPosition()?.pages ?? {})
  const [restoreY] = useState(() => readPosition()?.scrollY ?? null)
  const restored = useRef(false)
  const [current, setCurrent] = useState('')
  const [selected, setSelected] = useState<PickBook | null>(null)
  const rootRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    if (!ready) return
    const controller = new AbortController()
    let retried = false
    const load = async () => {
      try {
        const data = await fetchPicks(plan, controller.signal)
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

  // the blocks are sized under the sticky app header, whatever its height on this width
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
  const categories = data?.categories ?? []
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

  /*
   * One wheel step = the next or previous category (user 2026-10-12: "그냥 스크롤 한 단계 할 때마다 다음/이전 카테고리로
   * 이동하도록 해줘"), gliding there; from the last one down to the end of the page and back. A category picked from
   * the list still jumps instantly (user's earlier choice).
   * A burst of wheel events (fast notches, trackpad inertia) is one step: nothing more until the wheel has been still
   * for WHEEL_QUIET_MS. PageDown/PageUp/Space step the same way [안]. Left alone: the scrollbar (holding or dragging it
   * runs on, "스크롤바 누르고 위/아래로 마우스를 두면 스크롤이 쭉 가야"), touch scrolling [안], arrow keys, sideways and
   * Ctrl (zoom) wheels, and wheels inside the open list or windows. No correction after scrolling stops (a settle step
   * and snapping were tried and removed: "스크롤바 아래로 내렸는데 나를 위로 보내버려").
   */
  useEffect(() => {
    if (!codes) return
    const list = codes.split(',')
    let lastWheel = -Infinity
    const step = (down: boolean) => {
      const header = document.querySelector<HTMLElement>('[data-app-header]')?.offsetHeight ?? 0
      const max = document.documentElement.scrollHeight - window.innerHeight
      const places = list
        .map((code) => document.getElementById(sectionId(code)))
        .filter((section): section is HTMLElement => section !== null)
        .map((section) => Math.min(Math.round(window.scrollY + section.getBoundingClientRect().top - header), max))
      if (max > (places.at(-1) ?? 0) + 2) places.push(max)
      const y = window.scrollY
      const target = down ? places.find((place) => place > y + 2) : places.findLast((place) => place < y - 2)
      if (target !== undefined) glide(target)
    }
    // a short ease-out glide to the place ("휠로 이동할 때 통으로 좌표 바꾸지 말고 연속적으로 바뀌게 자연스럽게"); instant
    // with reduced motion. A mouse press (the scrollbar) or a touch stops it, and the user's scrolling wins.
    let frame = 0
    const stop = () => {
      cancelAnimationFrame(frame)
      frame = 0
    }
    const glide = (target: number) => {
      stop()
      if (window.matchMedia?.('(prefers-reduced-motion: reduce)').matches) {
        window.scrollTo({ top: target, behavior: 'instant' })
        return
      }
      const from = window.scrollY
      const start = performance.now()
      const tick = (now: number) => {
        const t = Math.min(1, (now - start) / GLIDE_MS)
        const eased = 1 - (1 - t) ** 3
        window.scrollTo({ top: Math.round(from + (target - from) * eased), behavior: 'instant' })
        frame = t < 1 ? requestAnimationFrame(tick) : 0
      }
      frame = requestAnimationFrame(tick)
    }
    const outside = (target: EventTarget | null) =>
      !(target instanceof Element && target.closest('[role="dialog"], [data-radix-popper-content-wrapper]'))
    // a mouse press (the scrollbar) stops a glide; a finger on a touch screen does not (one swipe at a time)
    const onPointerDown = (event: PointerEvent) => {
      if (event.pointerType !== 'touch') stop()
    }
    const onWheel = (event: WheelEvent) => {
      if (event.ctrlKey || event.shiftKey || Math.abs(event.deltaX) > Math.abs(event.deltaY) || event.deltaY === 0) return
      if (!outside(event.target)) return
      event.preventDefault()
      const now = event.timeStamp
      const quiet = now - lastWheel > WHEEL_QUIET_MS
      lastWheel = now
      // one step at a time: nothing more while a glide runs
      if (quiet && frame === 0) step(event.deltaY > 0)
    }
    const onKey = (event: KeyboardEvent) => {
      if (event.defaultPrevented || event.altKey || event.ctrlKey || event.metaKey || !outside(event.target)) return
      const typing = event.target instanceof Element && event.target.closest('input, textarea, select, [contenteditable]')
      if (typing) return
      let down: boolean
      if (event.key === 'PageDown') down = true
      else if (event.key === 'PageUp') down = false
      else if (event.key === ' ' && (event.target === document.body || event.target === document.documentElement)) {
        down = !event.shiftKey
      } else return
      event.preventDefault()
      if (frame === 0) step(down)
    }
    // touch screens: no free vertical scrolling here (CSS touch-action); one vertical swipe = one category, whatever its
    // strength, decided by the dominant axis; taps and sideways swipes (grid pages) are left alone
    const coarse = touchMode
    let touch: { x: number; y: number } | null = null
    const onTouchStart = (event: TouchEvent) => {
      touch = null
      if (!coarse) {
        stop()
        return
      }
      const zoomedIn = (window.visualViewport?.scale ?? 1) > 1.01
      if (event.touches.length !== 1 || zoomedIn || !outside(event.target)) return
      touch = { x: event.touches[0].clientX, y: event.touches[0].clientY }
    }
    const onTouchEnd = (event: TouchEvent) => {
      const start = touch
      touch = null
      const end = event.changedTouches[0]
      if (!start || !end) return
      const dx = end.clientX - start.x
      const dy = end.clientY - start.y
      if (Math.abs(dy) < SWIPE_Y_PX || Math.abs(dy) <= Math.abs(dx)) return
      if (frame === 0) step(dy < 0)
    }
    window.addEventListener('wheel', onWheel, { passive: false })
    window.addEventListener('keydown', onKey)
    document.addEventListener('pointerdown', onPointerDown, true)
    window.addEventListener('touchstart', onTouchStart, { passive: true })
    window.addEventListener('touchend', onTouchEnd, { passive: true })
    return () => {
      stop()
      window.removeEventListener('wheel', onWheel)
      window.removeEventListener('keydown', onKey)
      document.removeEventListener('pointerdown', onPointerDown, true)
      window.removeEventListener('touchstart', onTouchStart)
      window.removeEventListener('touchend', onTouchEnd)
    }
  }, [codes, touchMode])

  // pinch-zoomed in: the page scrolls normally again so nothing is out of reach (touch swipes step otherwise)
  const [zoomed, setZoomed] = useState(false)
  useEffect(() => {
    const viewport = window.visualViewport
    if (!viewport) return
    const check = () => setZoomed(viewport.scale > 1.01)
    check()
    viewport.addEventListener('resize', check)
    return () => viewport.removeEventListener('resize', check)
  }, [])
  const currentCode = categories.some((c) => c.code === current) ? current : (categories[0]?.code ?? '')

  // One usage report each time another field comes to the middle of the screen, the first one included; never again
  // for the same field while it stays there (cycle 15)
  const reportedCode = useRef('')
  useEffect(() => {
    if (!currentCode || reportedCode.current === currentCode) return
    reportedCode.current = currentCode
    // TODO(cycle 21 step 9-3): the list of the address; the screen shows the librarian picks for now
    sendUsageEvent({ type: 'picks_list_view', list: 'librarian', category: currentCode })
  }, [currentCode])

  // S13c: that field's books 1-9, the page put straight there with no smooth scrolling (user 2026-10-11)
  const jump = (code: string) => {
    setPages((before) => ({ ...before, [code]: 0 }))
    setCurrent(code)
    requestAnimationFrame(() => {
      document.getElementById(sectionId(code))?.scrollIntoView?.({ block: 'start', behavior: 'instant' })
    })
  }
  const pick = (book: PickBook) => {
    savePosition({ scrollY: window.scrollY })
    setSelected(book)
    // the book window opens only for a book with an e-book in my libraries (cycle 15 usage report)
    if (book.available && book.bookId != null) {
      sendUsageEvent({ type: 'picks_book_view', list: 'librarian', category: book.category, bookId: book.bookId })
    }
  }
  // [책 검색 >>] (AC-9, AC-20): from a phone window's own history entry the book screen replaces that entry, so back
  // comes to this screen, not to the closed window
  const openBook = (bookId: number) => {
    savePosition({ scrollY: window.scrollY })
    navigate(`/books/${bookId}`, { replace: onWindowEntry() })
  }

  let body
  if (!result) {
    body = <LoadingBlock />
  } else if (result.failed) {
    body = (
      <p className={styles.notice} role="alert">
        사서추천도서를 불러오지 못했어요. 잠시 뒤에 다시 해 주세요.
      </p>
    )
  } else if (categories.length === 0) {
    body = <p className={styles.empty}>아직 사서추천도서를 준비하고 있어요</p>
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
        <p className={styles.source}>{sourceLine(data?.fetchedAt ?? null)}</p>
      </>
    )
  }

  return (
    <div
      ref={rootRef}
      className={styles.page}
      data-zoomed={zoomed || undefined}
      data-touch={touchMode || undefined}
    >
      {/*
        Design A with the user's later choices (2026-10-12): no visible heading ("이거 위에 있는거 불필요하니까 없애고"),
        only one for screen readers; the category picker floats at the bottom centre and follows the category in view.
      */}
      <h1 className="visually-hidden">사서추천도서</h1>
      <div className={styles.blocks}>{body}</div>
      {categories.length > 0 && (
        <div className={styles.floating}>
          <CategoryPicker
            categories={categories}
            current={currentCode}
            onPick={jump}
            phone={phone}
            fetchedAt={data?.fetchedAt ?? null}
          />
        </div>
      )}
      <PicksBookDialog
        book={selected?.available && selected.bookId != null ? selected : null}
        categoryName={categories.find((c) => c.code === selected?.category)?.name ?? null}
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
        사서추천도서를 불러오는 중
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
