import { useId, type ReactNode } from 'react'
import type { TargetLibrary } from '../../api/types'
import type { ClickScreen } from '../../api/usageEvents'
import type { ResultGroup } from './resultGroups'
import { ResultRow, type RegionBasis } from './ResultRow'
import type { ResultRow as Row } from './sortBatch'
import styles from './ResultList.module.css'

interface Props {
  /** ① ② or ③; ④ (확인하지 못한 도서관) stays the list's own group (cycle 20). */
  group: ResultGroup
  /** "내 도서관에 있어요", "어디 살든 가입되는 도서관에 있어요", "그 밖의 도서관에 있어요" (문구표). */
  title: string
  /** The small text after the title (with its buttons); on phones on its own line under the title. */
  note?: ReactNode
  /** An empty ① ("내 도서관에는 없어요 (0)"): grey, no subsections. */
  muted?: boolean
  libraryById: ReadonlyMap<number, TargetLibrary>
  searchUrls: ReadonlyMap<number, string>
  screen?: ClickScreen
  tags: boolean
  regionBasis: RegionBasis
}

/**
 * One result group (cycle 22 R5, D3, ADR-0136): the title with its count and small text, then the subsections
 * "지금 빌릴 수 있어요", "예약하고 기다려요" and "대출 정보 조회 중", each holding every batch's rows of this group, a later
 * batch's rows at the end (R5-3). There is no divider between batches any more. In place of `BatchSection` on the
 * screens with groups.
 */
export function GroupSection({ group, title, note, muted = false, libraryById, searchUrls, screen, tags, regionBasis }: Props) {
  const id = useId()
  const renderRows = (rows: Row[]) => (
    <ol className={styles.rows}>
      {rows.map((row) => (
        <li key={`${row.libraryId}-${row.page}-${row.rank}`}>
          <ResultRow
            row={row}
            library={libraryById.get(row.libraryId)}
            searchUrl={searchUrls.get(row.libraryId)}
            screen={screen}
            tags={tags}
            regionBasis={regionBasis}
          />
        </li>
      ))}
    </ol>
  )
  const subsection = (key: 'borrow' | 'reserve' | 'pending', label: string, rows: Row[]) =>
    rows.length > 0 && (
      <section
        className={styles.group}
        data-group={key}
        aria-labelledby={`${id}-${key}`}
        aria-busy={key === 'pending' ? true : undefined}
      >
        <h3 id={`${id}-${key}`} className={styles.groupTitle}>
          {label} <span className={styles.groupCount}>({rows.length})</span>
        </h3>
        {renderRows(rows)}
      </section>
    )

  return (
    <section
      className={styles.resultGroup}
      data-result-group={group.key}
      data-muted={muted || undefined}
      aria-labelledby={`${id}-title`}
    >
      <header className={styles.resultGroupHead}>
        <h2 id={`${id}-title`} className={styles.resultGroupTitle}>
          {title} <span className={styles.resultGroupCount}>({group.count})</span>
        </h2>
        {note && <p className={styles.resultGroupNote}>{note}</p>}
      </header>
      {subsection('borrow', '지금 빌릴 수 있어요', group.available)}
      {subsection('reserve', '예약하고 기다려요', group.reserve)}
      {subsection('pending', '대출 정보 조회 중', group.pending)}
    </section>
  )
}
