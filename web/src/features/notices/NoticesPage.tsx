import { useQuery } from '@tanstack/react-query'
import { useEffect } from 'react'
import { Link, useSearchParams } from 'react-router'
import { fetchNotices, retryServerErrors } from '../../api/client'
import type { NoticeListItem } from '../../api/types'
import { LoadFailed, LoadingLines } from '../../components/ContentStates'
import { useMediaQuery } from '../../lib/useMediaQuery'
import { positiveNumber } from '../sitecontent/siteContent'
import styles from './Notices.module.css'

/** Router state a notice row passes to S17, so "목록으로" goes back to the page it came from. */
export interface FromNoticeList {
  noticeListSearch: string
}

/** The page numbers shown at once: 1~10 on PC and tablet, 5 with ‹ › on phones (flow S16). */
const PC_WINDOW = 10
const PHONE_WINDOW = 5

function pageHref(page: number): string {
  return page > 1 ? `/notices?page=${page}` : '/notices'
}

/**
 * S16 `/notices` (cycle 16): pinned notices on page 1, then the newest first, 10 a page with page numbers. A row is the
 * title and the posting date; the list never says a notice was edited (AC-13). Open without login. A page past the
 * last is moved by the server to the last one; the address then follows it.
 */
export function NoticesPage() {
  const [params, setParams] = useSearchParams()
  const phone = useMediaQuery('(width <= 640px)')
  const page = positiveNumber(params.get('page')) ?? 1
  const list = useQuery({
    queryKey: ['notices', page],
    queryFn: ({ signal }) => fetchNotices(page, signal),
    retry: retryServerErrors,
  })

  const shownPage = list.data?.page
  useEffect(() => {
    if (shownPage === undefined) return
    const wanted = shownPage > 1 ? String(shownPage) : null
    if (params.get('page') === wanted) return
    const next = new URLSearchParams(params)
    if (wanted === null) next.delete('page')
    else next.set('page', wanted)
    setParams(next, { replace: true })
  }, [shownPage, params, setParams])

  const from: FromNoticeList = { noticeListSearch: shownPage && shownPage > 1 ? `?page=${shownPage}` : '' }

  let content
  if (list.isPending) content = <LoadingLines lines={5} />
  else if (list.isError) content = <LoadFailed onRetry={() => void list.refetch()} />
  else if (list.data.pinned.length === 0 && list.data.items.length === 0) {
    content = <p className={styles.empty}>아직 공지가 없어요</p>
  } else {
    const { pinned, items, totalCount, pageSize } = list.data
    const totalPages = Math.max(1, Math.ceil(totalCount / Math.max(1, pageSize)))
    content = (
      <>
        <ul className={styles.list}>
          {pinned.map((notice) => (
            <NoticeRow key={`pinned-${notice.id}`} notice={notice} pinned from={from} />
          ))}
          {items.map((notice) => (
            <NoticeRow key={notice.id} notice={notice} from={from} />
          ))}
        </ul>
        <Pages page={list.data.page} totalPages={totalPages} phone={phone} />
      </>
    )
  }

  return (
    <section className={styles.card} aria-labelledby="notices-title">
      <h1 id="notices-title" className={styles.title}>
        공지사항
      </h1>
      {content}
    </section>
  )
}

function NoticeRow({ notice, pinned = false, from }: { notice: NoticeListItem; pinned?: boolean; from: FromNoticeList }) {
  return (
    <li>
      <Link to={`/notices/${notice.id}`} state={from} className={styles.row}>
        <span className={styles.rowTitle}>
          {pinned && <span className={styles.pinned}>고정</span>}
          {notice.title}
        </span>
        <time className={styles.rowDate} dateTime={notice.postedOn}>
          {notice.postedOn}
        </time>
      </Link>
    </li>
  )
}

/**
 * Page numbers in blocks (1~10, 11~20 on PC; 1~5, 6~10 on phones). ‹ › go one page back and forward: always on
 * phones, on PC only when there are more pages than one block. Nothing when there is only one page.
 */
function Pages({ page, totalPages, phone }: { page: number; totalPages: number; phone: boolean }) {
  if (totalPages < 2) return null
  const size = phone ? PHONE_WINDOW : PC_WINDOW
  const first = Math.floor((page - 1) / size) * size + 1
  const last = Math.min(totalPages, first + size - 1)
  const arrows = phone || totalPages > size
  const numbers = Array.from({ length: last - first + 1 }, (_, index) => first + index)
  return (
    <nav className={styles.pages} aria-label="쪽">
      {arrows && <Arrow to={page > 1 ? page - 1 : null} label="이전 쪽" text="‹" />}
      {numbers.map((number) => (
        <Link
          key={number}
          to={pageHref(number)}
          className={styles.pageLink}
          aria-current={number === page ? 'page' : undefined}
        >
          {number}
        </Link>
      ))}
      {arrows && <Arrow to={page < totalPages ? page + 1 : null} label="다음 쪽" text="›" />}
    </nav>
  )
}

function Arrow({ to, label, text }: { to: number | null; label: string; text: string }) {
  if (to === null) {
    return (
      <span className={styles.arrow} aria-label={label} aria-disabled="true" role="link">
        {text}
      </span>
    )
  }
  return (
    <Link to={pageHref(to)} className={styles.arrow} aria-label={label}>
      {text}
    </Link>
  )
}
