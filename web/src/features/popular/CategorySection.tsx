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

  return (
    <section id={sectionId(category.code)} className={styles.section} data-code={category.code} aria-labelledby={titleId}>
      <h2 id={titleId} className={styles.sectionTitle}>
        {category.name}
      </h2>
      <div className={styles.pager}>
        {hasPrev ? (
          <button
            type="button"
            className={styles.arrow}
            data-side="prev"
            aria-label={`${category.name} 이전 9권`}
            onClick={() => onPage(current - 1)}
          >
            <span aria-hidden="true">◀</span>
          </button>
        ) : (
          <span className={styles.arrowSpace} data-side="prev" />
        )}
        <ol
          className={styles.grid}
          start={current * PAGE_SIZE + 1}
          aria-label={`${category.name} ${current * PAGE_SIZE + 1}~${current * PAGE_SIZE + books.length}위`}
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
        </ol>
        {hasNext ? (
          <button
            type="button"
            className={styles.arrow}
            data-side="next"
            aria-label={`${category.name} 다음 9권`}
            onClick={() => onPage(current + 1)}
          >
            <span aria-hidden="true">▶</span>
          </button>
        ) : (
          <span className={styles.arrowSpace} data-side="next" />
        )}
      </div>
    </section>
  )
}
