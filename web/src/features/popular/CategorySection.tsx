import { useRef } from 'react'
import type { PopularBook, PopularCategory } from '../../api/types'
import { CoverCard } from './CoverCard'
import { pageCount, PAGE_SIZE, sectionId } from './popularText'
import styles from './PopularPage.module.css'

/** [안] (cycle 10 step 7-4): how far a finger must move sideways to turn the page on phones and tablets. */
export const SWIPE_PX = 50

type Props = {
  category: PopularCategory
  /** 0 = ranks 1-9 */
  page: number
  onPage: (page: number) => void
  onPick: (book: PopularBook) => void
}

/**
 * One category block (R4-1, R4-2): its name, then ◀ 3 x 3 ▶. The arrows turn 9 books at a time; none before the
 * first page, none after the last (46-50, five books) (AC-2). Touch screens also turn it with a sideways swipe
 * (AC-19); a mouse drag does nothing.
 */
export function CategorySection({ category, page, onPage, onPick }: Props) {
  const pages = pageCount(category.books.length)
  const current = Math.min(Math.max(page, 0), pages - 1)
  const books = category.books.slice(current * PAGE_SIZE, current * PAGE_SIZE + PAGE_SIZE)
  const swipe = useRef<{ x: number; y: number } | null>(null)
  const swiped = useRef(false)
  const titleId = `${sectionId(category.code)}-title`
  const hasPrev = current > 0
  const hasNext = current < pages - 1
  const first = current * PAGE_SIZE + 1
  const last = current * PAGE_SIZE + books.length

  return (
    <section id={sectionId(category.code)} className={styles.section} data-code={category.code} aria-labelledby={titleId}>
      <div className={styles.panel}>
        <div className={styles.cardHead}>
          <h2 id={titleId} className={styles.sectionTitle}>
            {category.name}
          </h2>
          <span className={styles.range}>
            <b>
              {first}–{last}위
            </b>{' '}
            / {category.books.length}
          </span>
        </div>
        <div className={styles.pager}>
          {hasPrev ? (
            <button
              type="button"
              className={styles.arrow}
              data-side="prev"
              aria-label={`${category.name} 이전 9권`}
              onClick={() => onPage(current - 1)}
            >
              <Chevron side="prev" />
            </button>
          ) : (
            <span className={styles.arrowSpace} data-side="prev" />
          )}
          <ol
            className={styles.grid}
            start={first}
            aria-label={`${category.name} ${first}~${last}위`}
            onPointerDown={(event) => {
              swiped.current = false
              swipe.current = event.pointerType === 'mouse' ? null : { x: event.clientX, y: event.clientY }
            }}
            onPointerUp={(event) => {
              const start = swipe.current
              swipe.current = null
              if (!start) return
              const dx = event.clientX - start.x
              const dy = event.clientY - start.y
              if (Math.abs(dx) < SWIPE_PX || Math.abs(dx) <= Math.abs(dy)) return
              if (dx < 0 && hasNext) onPage(current + 1)
              else if (dx > 0 && hasPrev) onPage(current - 1)
              else return
              // the tap that ends a swipe does not open the book
              swiped.current = true
            }}
            onPointerCancel={() => {
              swipe.current = null
            }}
            onClickCapture={(event) => {
              if (!swiped.current) return
              swiped.current = false
              event.preventDefault()
              event.stopPropagation()
            }}
          >
            {books.map((book) => (
              <CoverCard key={book.rank} book={book} onPick={onPick} />
            ))}
            {/* the last page (46-50) keeps the 3 x 3 space, so the panel does not shrink or move (user 2026-10-12) */}
            {Array.from({ length: PAGE_SIZE - books.length }, (_, index) => (
              <li key={`empty-${index}`} className={styles.cell} aria-hidden="true" data-empty="">
                <span className={styles.emptyCell} />
              </li>
            ))}
          </ol>
          {hasNext ? (
            <button
              type="button"
              className={styles.arrow}
              data-side="next"
              aria-label={`${category.name} 다음 9권`}
              onClick={() => onPage(current + 1)}
            >
              <Chevron side="next" />
            </button>
          ) : (
            <span className={styles.arrowSpace} data-side="next" />
          )}
        </div>
        {/* where this page is among the six (design A); the arrows and the list name say it to screen readers */}
        <div className={styles.dots} aria-hidden="true">
          {Array.from({ length: pages }, (_, index) => (
            <i key={index} data-on={index === current} />
          ))}
        </div>
      </div>
    </section>
  )
}

function Chevron({ side }: { side: 'prev' | 'next' }) {
  return (
    <svg
      width="16"
      height="16"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="2.6"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
      focusable="false"
    >
      <path d={side === 'prev' ? 'M15 5l-7 7 7 7' : 'M9 5l7 7-7 7'} />
    </svg>
  )
}
