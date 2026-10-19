import { Popover } from 'radix-ui'
import type { TargetLibrary } from '../../api/types'
import { libraryClick } from '../../lib/libraryClick'
import { useLibraryLink } from '../../lib/libraryLink'
import { safeHttpUrl } from '../../lib/safeUrl'
import type { LibraryResult } from './useSearch'
import styles from './LibrarySummaryBar.module.css'
import { Cross } from '../../components/icons'

/** More libraries than this collapse into "… 외 N곳", which opens the full list (U-8). */
export const VISIBLE_LIBRARIES = 5

interface Props {
  /** Requested libraries only, those with results first [확정]. */
  libraries: TargetLibrary[]
  results: Record<number, LibraryResult>
  /** Whether the library has another page to fetch (plan state). */
  hasMore: (libraryId: number) => boolean
  onMore: (libraryId: number) => void
}

/**
 * Direct search on every width (user requests 2026-10-05): no row of libraries, only an "N곳" button that opens the
 * same full list as "… 외 N곳". It sits on the "상세검색 · 설정" line on phones and at the right end of the scope controls' row on wider
 * screens; the list opens below it, never past the window.
 */
export function LibraryCountButton({ libraries, results, hasMore, onMore }: Props) {
  return (
    <AllLibraries
      libraries={libraries}
      hidden={libraries}
      results={results}
      hasMore={hasMore}
      onMore={onMore}
      compact
    />
  )
}

/** One cell per requested library: state, fetched/total, cache age, the library's own 더 보기. */
export function LibrarySummaryBar({ libraries, results, hasMore, onMore }: Props) {
  const visible = libraries.slice(0, VISIBLE_LIBRARIES)
  const hidden = libraries.slice(VISIBLE_LIBRARIES)

  return (
    <div className={styles.wrap}>
      <ul className={styles.bar} aria-label="조회한 도서관">
        {visible.map((library) => (
          <li key={library.id} className={styles.cell} data-status={results[library.id]?.status}>
            <LibraryCell library={library} result={results[library.id]} hasMore={hasMore(library.id)} onMore={onMore} />
          </li>
        ))}
        {hidden.length > 0 && (
          <li>
            <AllLibraries libraries={libraries} hidden={hidden} results={results} hasMore={hasMore} onMore={onMore} />
          </li>
        )}
      </ul>
    </div>
  )
}

function AllLibraries({
  libraries,
  hidden,
  results,
  hasMore,
  onMore,
  compact = false,
}: Props & { hidden: TargetLibrary[]; compact?: boolean }) {
  const pending = hidden.filter((l) => !results[l.id] || results[l.id].status === 'loading').length
  return (
    <Popover.Root>
      <Popover.Trigger
        className={compact ? styles.count : styles.overflow}
        aria-label={`도서관 ${libraries.length}곳 모두 보기`}
      >
        {compact ? (
          `${libraries.length}곳`
        ) : (
          <>
            <span aria-hidden="true">…</span> 외 {hidden.length}곳
          </>
        )}
        {pending > 0 && <span className={styles.spinner} aria-hidden="true" />}
      </Popover.Trigger>
      <Popover.Portal>
        <Popover.Content
          className={styles.popover}
          data-compact={compact || undefined}
          side="bottom"
          align={compact ? 'end' : 'start'}
          sideOffset={8}
          collisionPadding={12}
        >
          <p className={styles.popoverTitle}>조회한 도서관 {libraries.length}곳</p>
          <ul className={styles.popoverList}>
            {libraries.map((library) => (
              <li key={library.id} className={styles.popoverRow} data-status={results[library.id]?.status}>
                <LibraryCell library={library} result={results[library.id]} hasMore={hasMore(library.id)} onMore={onMore} />
              </li>
            ))}
          </ul>
          <Popover.Close className={styles.popoverClose} aria-label="닫기">
            <Cross />
          </Popover.Close>
        </Popover.Content>
      </Popover.Portal>
    </Popover.Root>
  )
}

function LibraryCell({
  library,
  result,
  hasMore,
  onMore,
}: {
  library: TargetLibrary
  result: LibraryResult | undefined
  hasMore: boolean
  onMore: (libraryId: number) => void
}) {
  // the row's own front page: the e-library's main page for an e-library row; the same tab on touch screens
  const homepage = safeHttpUrl(library.homepageUrl)
  const link = useLibraryLink()
  return (
    <>
      {homepage ? (
        <a
          className={`${styles.name} ${styles.nameLink}`}
          href={homepage}
          {...link.props}
          {...libraryClick(library.id, 'summary_bar', { screen: 'direct_results' })}
        >
          {library.name}
          <span className="visually-hidden"> 전자도서관 첫 화면{link.newTab && ' (새 창)'}</span>
        </a>
      ) : (
        <span className={styles.name}>{library.name}</span>
      )}
      <LibraryStatus result={result} />
      {hasMore && result && (
        <button
          type="button"
          className={styles.more}
          disabled={result.more === 'loading'}
          onClick={() => onMore(library.id)}
        >
          {result.more === 'loading' ? '불러오는 중…' : result.more === 'failed' ? '다시 시도' : '더 보기'}
        </button>
      )}
    </>
  )
}

function LibraryStatus({ result }: { result: LibraryResult | undefined }) {
  if (!result || result.status === 'loading') {
    return (
      <span className={styles.status}>
        <span className={styles.spinner} aria-hidden="true" />
        조회 중
      </span>
    )
  }
  if (result.status === 'failed') return <span className={styles.status}>조회 실패</span>

  const first = result.pages[0]
  const fetched = result.pages.reduce((sum, page) => sum + page.items.length, 0)
  const total = result.pages.reduce((max, page) => Math.max(max, page.totalCount), 0)
  return (
    <span className={styles.status}>
      {total === 0 ? '결과 없음' : `${fetched}/${total}건`}
      {first?.cached && <span className={styles.age}>{cacheAge(first.cacheAgeSeconds)}</span>}
      {result.more === 'failed' && <span className={styles.moreFailed}>더 보기 실패</span>}
    </span>
  )
}

/** Server-computed age; under a minute reads "방금 조회" (design-delta). */
export function cacheAge(seconds: number): string {
  return seconds < 60 ? '방금 조회' : `${Math.floor(seconds / 60)}분 전 조회`
}
