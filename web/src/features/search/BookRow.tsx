import { Link } from 'react-router'
import type { BookItem } from '../../api/types'
import { ChevronRight } from '../../components/icons'
import styles from './BookList.module.css'

type Props = {
  book: BookItem
  /** Address part the book page keeps: filter, scope, visit (the same as `BookList`'s). */
  bookLinkSearch: string
}

/**
 * One book as a line: title, author · publisher · year, "보유 도서관 N곳", chevron; a tap opens the book screen
 * (`/books/{id}`). Shared by 책 먼저 고르기 (`BookList`) and, since cycle 28 (ADR-0173), by 바로 보기 when the first
 * holding stream was refused with 503 and only the book list can be shown [확정]. `BookList` fetches `/books` itself,
 * so the line alone is shared, not the list.
 */
export function BookRow({ book, bookLinkSearch }: Props) {
  return (
    <Link className={styles.book} to={`/books/${book.id}${bookLinkSearch ? `?${bookLinkSearch}` : ''}`}>
      <span className={styles.title}>{book.title}</span>
      <span className={styles.meta}>
        {[book.authorDisplay, book.publisher, book.publishedOn?.slice(0, 4)].filter(Boolean).join(' · ') ||
          '저자·출판사 정보 없음'}
      </span>
      <span className={styles.count}>보유 도서관 {book.holdingLibraryCount}곳</span>
      <span className={styles.chevron} aria-hidden="true">
        <ChevronRight size={8} />
      </span>
    </Link>
  )
}
