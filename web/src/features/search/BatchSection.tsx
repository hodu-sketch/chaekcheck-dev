import type { ResultFilter, TargetLibrary } from '../../api/types'
import type { ClickScreen } from '../../api/usageEvents'
import { emptyResultText } from './resultFilter'
import { ResultRow } from './ResultRow'
import type { Batch } from './rowVerdict'
import type { ResultRow as Row, SortedBatch } from './sortBatch'
import styles from './ResultList.module.css'

interface Props {
  batch: Batch
  sorted: SortedBatch
  filter: ResultFilter
  /** Libraries answered so far in this search, for "지금까지 N곳에서 찾지 못했습니다". */
  answeredSoFar: number
  /** Of those, the libraries whose lookup failed (cycle 20): counted apart as "N곳은 확인하지 못했어요". */
  uncheckedSoFar: number
  /** Another batch may follow ("더 보기" is shown under the list). */
  moreToCome: boolean
  first: boolean
  libraryById: ReadonlyMap<number, TargetLibrary>
  searchUrls: ReadonlyMap<number, string>
  screen?: ClickScreen
}

/**
 * One batch: a divider from the second batch on, then "지금 빌릴 수 있어요", "예약하고 기다려요" and the rows still
 * being checked ("대출 정보 조회 중"). Rows move between sections only within this batch. The rows that could not be
 * checked are not here: the list gathers them from every batch into 「확인하지 못한 도서관」 at the bottom (cycle 20).
 */
export function BatchSection({
  batch,
  sorted,
  filter,
  answeredSoFar,
  uncheckedSoFar,
  moreToCome,
  first,
  libraryById,
  searchUrls,
  screen,
}: Props) {
  const idPrefix = `batch-${batch.batchNo}`
  const shown = sorted.available.length + sorted.reserve.length + sorted.pending.length
  const libraries = new Set(batch.requestedLibraries).size

  const renderRows = (rows: Row[]) => (
    <ol className={styles.rows}>
      {rows.map((row) => (
        <li key={`${row.libraryId}-${row.page}-${row.rank}`}>
          <ResultRow
            row={row}
            library={libraryById.get(row.libraryId)}
            searchUrl={searchUrls.get(row.libraryId)}
            screen={screen}
          />
        </li>
      ))}
    </ol>
  )

  return (
    <section className={styles.batch} aria-label={first ? '검색 결과' : `더 찾은 결과 ${batch.batchNo}`}>
      {!first && (
        <p className={styles.divider}>
          <span>
            더 찾은 결과 · 도서관 {libraries}곳{batch.status === 'loading' && ' 조회 중'}
          </span>
        </p>
      )}

      {shown === 0 && batch.status === 'done' && moreToCome && (
        <p className={styles.empty}>{emptyResultText(answeredSoFar, uncheckedSoFar, filter)}</p>
      )}

      {sorted.available.length > 0 && (
        <section className={styles.group} data-group="borrow" aria-labelledby={`${idPrefix}-borrow`}>
          <h2 id={`${idPrefix}-borrow`} className={styles.groupTitle}>
            지금 빌릴 수 있어요 <span className={styles.groupCount}>({sorted.available.length})</span>
          </h2>
          {renderRows(sorted.available)}
        </section>
      )}

      {sorted.reserve.length > 0 && (
        <section className={styles.group} data-group="reserve" aria-labelledby={`${idPrefix}-reserve`}>
          <h2 id={`${idPrefix}-reserve`} className={styles.groupTitle}>
            예약하고 기다려요 <span className={styles.groupCount}>({sorted.reserve.length})</span>
          </h2>
          {renderRows(sorted.reserve)}
        </section>
      )}

      {sorted.pending.length > 0 && (
        <section className={styles.group} data-group="pending" aria-labelledby={`${idPrefix}-pending`} aria-busy="true">
          <h2 id={`${idPrefix}-pending`} className={styles.groupTitle}>
            대출 정보 조회 중 <span className={styles.groupCount}>({sorted.pending.length})</span>
          </h2>
          {renderRows(sorted.pending)}
        </section>
      )}
    </section>
  )
}
