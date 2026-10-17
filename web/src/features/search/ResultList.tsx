import type { ReactNode } from 'react'
import { ToggleGroup } from 'radix-ui'
import type { ResultFilter, TargetLibrary } from '../../api/types'
import type { ClickScreen } from '../../api/usageEvents'
import { BatchSection } from './BatchSection'
import { BLOCKED_TEXT, type Blocked } from './blocked'
import { ResultRow, UncheckedLibraryLine } from './ResultRow'
import type { Batch } from './rowVerdict'
import type { ResultRow as Row, SortedBatch } from './sortBatch'
import styles from './ResultList.module.css'

export interface BatchView {
  batch: Batch
  sorted: SortedBatch
  answeredSoFar: number
  /** Libraries whose lookup failed so far (cycle 20); 0 when the caller does not count them. */
  uncheckedSoFar?: number
}

/** A library of a direct search whose page could not be read (cycle 20 review #1): one line, no book. */
export interface UncheckedLibrary {
  library: TargetLibrary
  /** The library's own site, when known. */
  url: string | null
}

/** The title of the group of rows that could not be checked (cycle 20 handoff 1-13) [확정]. */
export const UNCHECKED_GROUP_TITLE = '확인하지 못한 도서관'

interface Props {
  batches: BatchView[]
  filter: ResultFilter
  onFilterChange: (filter: ResultFilter) => void
  libraries: TargetLibrary[]
  searchUrls: ReadonlyMap<number, string>
  /** Libraries that still have something to fetch. */
  remaining: number
  /** A batch stream is open. */
  loading: boolean
  /** Libraries being asked in the open batch. */
  loadingLibraries: number
  batchFailed: boolean
  /** The last stream or page was refused with 429 or 503: one line above the results, which stay (cycle 08 R2). */
  blocked?: Blocked | null
  onMore: () => void
  /** Text after "더 보기"; default "(남은 도서관 N곳)". */
  moreText?: string
  /** Text while a batch is open; default "도서관 N곳 조회 중". */
  loadingText?: string
  /** Text when nothing is left; default "모든 도서관을 조회했습니다". */
  finishedText?: string
  /** Hides the result filter (a page that sets it elsewhere). */
  hideFilter?: boolean
  /** Shown at the right end of the filter line: the sort (user request 2026-10-05). */
  toolbarEnd?: ReactNode
  /** The screen, for the usage report of the result links (cycle 15). */
  screen?: ClickScreen
  /** Direct search (cycle 20): libraries that failed as a whole, one line each in 「확인하지 못한 도서관」. */
  uncheckedLibraries?: UncheckedLibrary[]
}

/**
 * The batches in the order they arrived, then the list-bottom "더 보기" (next libraries, then next pages). Changing
 * the filter searches again from the first batch [확정].
 */
export function ResultList({
  batches,
  filter,
  onFilterChange,
  libraries,
  searchUrls,
  remaining,
  loading,
  loadingLibraries,
  batchFailed,
  blocked,
  onMore,
  moreText,
  loadingText,
  finishedText,
  hideFilter,
  toolbarEnd,
  screen,
  uncheckedLibraries = [],
}: Props) {
  const libraryById = new Map(libraries.map((l) => [l.id, l]))
  // 「확인하지 못한 도서관」 (cycle 20 handoff 1-13 [확정], review #2): every batch's failed and UNKNOWN rows and the failed
  // libraries of a direct search, in plan order, as one group right above "더 보기"; shown in both filters
  const planIndex = new Map(libraries.map((l, index) => [l.id, index]))
  const at = (libraryId: number) => planIndex.get(libraryId) ?? Number.MAX_SAFE_INTEGER
  const unchecked: Array<{ key: string; order: number; rank: number; row?: Row; library?: UncheckedLibrary }> = [
    ...batches.flatMap((view) =>
      view.sorted.unchecked.map((row) => ({
        key: `${row.libraryId}-${row.page}-${row.rank}`,
        order: at(row.libraryId),
        rank: row.rank,
        row,
      })),
    ),
    ...uncheckedLibraries.map((library) => ({
      key: `library-${library.library.id}`,
      order: at(library.library.id),
      rank: -1,
      library,
    })),
  ].sort((a, b) => a.order - b.order || a.rank - b.rank)

  return (
    <section className={styles.list}>
      {blocked && (
        <p className={styles.blocked} role="alert">
          {BLOCKED_TEXT[blocked]}
        </p>
      )}
      <div className={styles.toolbar}>
      {!hideFilter && (
        <ToggleGroup.Root
          type="single"
          value={filter}
          onValueChange={(value) => value && onFilterChange(value as ResultFilter)}
          className={styles.filter}
          aria-label="결과 거르기"
        >
          {/* the default first, on the left (user request 2026-10-05) */}
          <ToggleGroup.Item value="available" className={styles.filterItem}>
            지금 빌릴 수 있는 책만
          </ToggleGroup.Item>
          <ToggleGroup.Item value="all" className={styles.filterItem}>
            전체
          </ToggleGroup.Item>
        </ToggleGroup.Root>
      )}
        {toolbarEnd && <div className={styles.toolbarEnd}>{toolbarEnd}</div>}
      </div>

      {batches.map((view, index) => (
        <BatchSection
          key={view.batch.batchNo}
          batch={view.batch}
          sorted={view.sorted}
          filter={filter}
          answeredSoFar={view.answeredSoFar}
          uncheckedSoFar={view.uncheckedSoFar ?? 0}
          moreToCome={remaining > 0}
          first={index === 0}
          libraryById={libraryById}
          searchUrls={searchUrls}
          screen={screen}
        />
      ))}

      {unchecked.length > 0 && (
        <section className={styles.group} data-group="unchecked" aria-labelledby="unchecked-title">
          <h2 id="unchecked-title" className={styles.groupTitle}>
            {UNCHECKED_GROUP_TITLE} <span className={styles.groupCount}>({unchecked.length})</span>
          </h2>
          <ol className={styles.rows}>
            {unchecked.map((item) => (
              <li key={item.key}>
                {item.row ? (
                  <ResultRow
                    row={item.row}
                    library={libraryById.get(item.row.libraryId)}
                    searchUrl={searchUrls.get(item.row.libraryId)}
                    screen={screen}
                  />
                ) : (
                  item.library && (
                    <UncheckedLibraryLine library={item.library.library} url={item.library.url} screen={screen} />
                  )
                )}
              </li>
            ))}
          </ol>
        </section>
      )}

      <div className={styles.bottom}>
        {loading ? (
          <p className={styles.loadingMore} role="status">
            <span className={styles.spinner} aria-hidden="true" />
            {loadingText ?? `도서관 ${loadingLibraries}곳 조회 중`}
          </p>
        ) : remaining > 0 ? (
          <>
            {batchFailed && !blocked && (
              <p className={styles.moreFailed} role="alert">
                더 찾지 못했어요. 다시 시도해 주세요.
              </p>
            )}
            <button type="button" className={styles.moreButton} onClick={onMore}>
              더 보기 <span className={styles.moreCount}>{moreText ?? `(남은 도서관 ${remaining}곳)`}</span>
            </button>
          </>
        ) : (
          <p className={styles.finished}>{finishedText ?? '모든 도서관을 조회했습니다'}</p>
        )}
      </div>
    </section>
  )
}
