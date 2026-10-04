import type { ReactNode } from 'react'
import { ToggleGroup } from 'radix-ui'
import type { ResultFilter, TargetLibrary } from '../../api/types'
import { BatchSection } from './BatchSection'
import type { Batch } from './rowVerdict'
import type { SortedBatch } from './sortBatch'
import styles from './ResultList.module.css'

export interface BatchView {
  batch: Batch
  sorted: SortedBatch
  answeredSoFar: number
}

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
  onMore,
  moreText,
  loadingText,
  finishedText,
  hideFilter,
  toolbarEnd,
}: Props) {
  const libraryById = new Map(libraries.map((l) => [l.id, l]))

  return (
    <section className={styles.list}>
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
          moreToCome={remaining > 0}
          first={index === 0}
          libraryById={libraryById}
          searchUrls={searchUrls}
        />
      ))}

      <div className={styles.bottom}>
        {loading ? (
          <p className={styles.loadingMore} role="status">
            <span className={styles.spinner} aria-hidden="true" />
            {loadingText ?? `도서관 ${loadingLibraries}곳 조회 중`}
          </p>
        ) : remaining > 0 ? (
          <>
            {batchFailed && (
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
