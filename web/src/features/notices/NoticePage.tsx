import { useQuery } from '@tanstack/react-query'
import { Link, useLocation, useParams } from 'react-router'
import { fetchNotice, notFoundCode, retryServerErrors } from '../../api/client'
import { LoadFailed, LoadingLines, NotFound } from '../../components/ContentStates'
import { Markdown } from '../../components/Markdown'
import { EarlierVersionMark, VersionPicker } from '../../components/VersionPicker'
import { positiveNumber, useVersionParam } from '../sitecontent/siteContent'
import type { FromNoticeList } from './NoticesPage'
import styles from './Notices.module.css'

/** The list page a row was opened from (router state), or the first page when the notice was opened directly. */
function listHref(state: unknown): string {
  const search = (state as Partial<FromNoticeList> | null)?.noticeListSearch
  return typeof search === 'string' && /^(\?page=[1-9][0-9]*)?$/.test(search) ? `/notices${search}` : '/notices'
}

/**
 * S17 `/notices/:noticeId` (cycle 16): title, posting date ("2026-11-03 · 고침 2026-11-04" for an edited notice), the
 * date picker when there is more than one version, the text as markdown (C2) and "목록으로" back to the list page it
 * came from. No previous or next notice. A hidden or missing notice is "찾을 수 없어요" (AC-5), so is a missing version
 * (AC-9).
 */
export function NoticePage() {
  const { noticeId } = useParams()
  const location = useLocation()
  const id = positiveNumber(noticeId ?? null)
  const version = useVersionParam()
  const toList = listHref(location.state)
  const query = useQuery({
    queryKey: ['notice', id, version],
    queryFn: ({ signal }) => fetchNotice(id!, version === 'invalid' ? null : version, signal),
    enabled: id !== null && version !== 'invalid',
    retry: retryServerErrors,
  })

  const code = query.isError ? notFoundCode(query.error) : null
  let content
  if (id === null || code === 'notice_not_found') {
    content = <NotFound to={toList} label="공지 목록 보기" />
  } else if (version === 'invalid' || code === 'version_not_found') {
    content = <NotFound to={location.pathname} state={location.state} label="지금 버전 보기" />
  } else if (query.isPending) {
    content = <LoadingLines />
  } else if (query.isError) {
    content = <LoadFailed onRetry={() => void query.refetch()} />
  } else {
    const notice = query.data
    content = (
      <>
        <header className={styles.head}>
          <h1 className={styles.title}>
            {notice.title}
            {!notice.version.current && <EarlierVersionMark />}
          </h1>
          <div className={styles.meta}>
            <span>
              {notice.postedOn}
              {notice.editedOn && ` · 고침 ${notice.editedOn}`}
            </span>
            <VersionPicker versions={notice.versions} shown={notice.version.number} />
          </div>
        </header>
        <Markdown>{notice.version.body}</Markdown>
      </>
    )
  }

  return (
    <article className={styles.card}>
      {content}
      <div className={styles.back}>
        <Link to={toList} className={styles.backLink}>
          목록으로
        </Link>
      </div>
    </article>
  )
}
