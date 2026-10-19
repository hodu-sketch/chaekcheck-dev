import type { ReactNode } from 'react'
import { ToggleGroup } from 'radix-ui'
import type { LibraryScope, ResultFilter, TargetLibrary, VisitFilter } from '../../api/types'
import type { ClickScreen } from '../../api/usageEvents'
import { BatchSection } from './BatchSection'
import { BLOCKED_TEXT, type Blocked } from './blocked'
import { GroupSection } from './GroupSection'
import { emptyResultText } from './resultFilter'
import { groupBatches } from './resultGroups'
import { ResultRow, UncheckedLibraryLine, type RegionBasis } from './ResultRow'
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

/**
 * What a grouped result list knows about its search, and the buttons inside the results (cycle 22 R4-2, R5, R8). The
 * line R7 of a search made without regions is the condition card's above the list now (user 2026-10-19).
 * The four buttons search again at once (R8-3): the caller changes the address and takes the snapshot again.
 */
export interface ResultSearchContext {
  /** The search was made with regions or added libraries (the snapshot, D6): an empty ① says so (R5-4). */
  hasMine: boolean
  /** "서울 강남구" for ①'s small text (the regions the search was made with); null when it had added libraries only. */
  mineLabel: string | null
  regionBasis: RegionBasis
  scope: LibraryScope
  visit: VisitFilter
  /** [전국에서 찾기]: the same search with `scope=all` ([전국에서 더 찾기] at the bottom taken out, user 2026-10-19: "빼자 불필요해"). */
  onSearchAll: () => void
  /** [온라인 가입만 보기]: `visit=none`, kept for later searches too. */
  onVisitNone: () => void
  /** [모두 보기]: back to `visit` any. */
  onVisitBack: () => void
}

/** The texts of the grouped list (cycle 22 문구표) [확정]. */
export const GROUP_TEXT = {
  mine: '내 도서관에 있어요',
  mineEmpty: '내 도서관에는 없어요',
  mineNote: (place: string | null) => (place ? `${place}에서 가입할 수 있는 곳과 직접 추가한 곳` : '직접 추가한 곳'),
  open: '어디 살든 가입되는 도서관에 있어요',
  openNote: '온라인으로 바로 가입할 수 있어요',
  visitSome: (sites: number) => `이 중 ${sites}곳은 가입하려면 도서관에 가야 해요`,
  // the same switch as 「온라인에서 가입 가능한 곳만 보기」 in the 찾을 도서관 box (user 2026-10-19)
  visitRemove: '온라인 가입만 보기',
  visitRemoved: '온라인으로 가입할 수 있는 곳만 보는 중이에요',
  visitBack: '모두 보기',
  other: '그 밖의 도서관에 있어요',
  otherNote: '그 지역에 살거나 일해야 가입돼요',
  searchAll: '전국에서 찾기',
} as const

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
  /**
   * The button's own words in place of "더 보기", saying what a tap does; `moreText` then goes on a small second line
   * (바로 보기, user request 2026-10-18: "결과 더 보기" / "다음 책 N권 보기").
   */
  moreLabel?: string
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
  /**
   * Cycle 22 (ADR-0136): the rows in result groups ① 내 도서관 ② 어디 살든 가입 ③ 그 밖 ④ 확인하지 못한 도서관. False keeps
   * the batches as they arrived (the book page, AC-17).
   */
  grouped?: boolean
  /** The pills after the library names (cycle 22 R6). */
  tags?: boolean
  /** Grouped lists of the search screen: the 전국 links and the 방문 가입 buttons. */
  searchContext?: ResultSearchContext
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
  moreLabel,
  loadingText,
  finishedText,
  hideFilter,
  toolbarEnd,
  screen,
  uncheckedLibraries = [],
  grouped = true,
  tags = false,
  searchContext,
}: Props) {
  const libraryById = new Map(libraries.map((l) => [l.id, l]))
  const context = grouped ? searchContext : undefined
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

      {grouped ? (
        <Groups
          batches={batches}
          libraryById={libraryById}
          searchUrls={searchUrls}
          screen={screen}
          tags={tags}
          context={context}
        />
      ) : (
        batches.map((view, index) => (
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
        ))
      )}

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
                    tags={tags}
                    regionBasis={context?.regionBasis}
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
        {grouped && !loading && remaining > 0 && <NothingYet batches={batches} filter={filter} />}
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
            <div className={styles.bottomButtons}>
              {moreLabel ? (
                <button type="button" className={`${styles.moreButton} ${styles.moreTwoLines}`} onClick={onMore}>
                  {moreLabel}
                  {moreText && <span className={styles.moreHint}>{moreText}</span>}
                </button>
              ) : (
                <button type="button" className={styles.moreButton} onClick={onMore}>
                  더 보기 <span className={styles.moreCount}>{moreText ?? `(남은 도서관 ${remaining}곳)`}</span>
                </button>
              )}
            </div>
          </>
        ) : (
          <>
            <p className={styles.finished}>{finishedText ?? '모든 도서관을 조회했습니다'}</p>
          </>
        )}
      </div>
    </section>
  )
}

/**
 * "지금까지 N곳에서 … 찾지 못했습니다" (R5-3, D3): one line at the list bottom right above 「결과 더 보기」, while no row is
 * shown yet in ①②③ and the last batch has answered; the batches no longer each have one.
 */
function NothingYet({ batches, filter }: { batches: BatchView[]; filter: ResultFilter }) {
  const last = batches.at(-1)
  if (!last || last.batch.status !== 'done') return null
  const shown = batches.some((view) => view.sorted.available.length + view.sorted.reserve.length + view.sorted.pending.length > 0)
  if (shown) return null
  const text = emptyResultText(last.answeredSoFar, last.uncheckedSoFar ?? 0, filter)
  return text ? <p className={styles.empty}>{text}</p> : null
}

function regionBasisOf(context: ResultSearchContext | undefined): RegionBasis {
  return context?.regionBasis ?? 'both'
}

/**
 * ① ② ③ of a grouped list (R5): ① first, then ② and ③, each only when it has rows; ① also when empty, as "내 도서관에는
 * 없어요 (0)", once the first batch has answered a search made with regions or added libraries (R5-4).
 */
function Groups({
  batches,
  libraryById,
  searchUrls,
  screen,
  tags,
  context,
}: {
  batches: BatchView[]
  libraryById: ReadonlyMap<number, TargetLibrary>
  searchUrls: ReadonlyMap<number, string>
  screen?: ClickScreen
  tags: boolean
  context: ResultSearchContext | undefined
}) {
  const { groups, visitRequiredOpenSites } = groupBatches(batches, libraryById)
  const [mine, open, other] = groups
  const firstDone = batches[0]?.batch.status === 'done'
  const regionBasis = regionBasisOf(context)
  const common = { libraryById, searchUrls, screen, tags, regionBasis }
  const linkButton = (label: string, onClick: () => void) => (
    <button type="button" className={styles.linkButton} onClick={onClick}>
      {label}
    </button>
  )
  const visitRemoved = context?.visit === 'none'
  const visitLine = context
    ? visitRemoved
      ? (
          <span className={styles.noteAside}>
            {GROUP_TEXT.visitRemoved} · {linkButton(GROUP_TEXT.visitBack, context.onVisitBack)}
          </span>
        )
      : visitRequiredOpenSites > 0 && (
          <span className={styles.noteAside}>
            {GROUP_TEXT.visitSome(visitRequiredOpenSites)}{' '}
            {linkButton(GROUP_TEXT.visitRemove, context.onVisitNone)}
          </span>
        )
    : null

  return (
    <>
      {mine.count > 0 ? (
        <GroupSection
          group={mine}
          title={GROUP_TEXT.mine}
          note={context && GROUP_TEXT.mineNote(context.mineLabel)}
          {...common}
        />
      ) : (
        context?.hasMine &&
        firstDone && (
          <GroupSection
            group={mine}
            title={GROUP_TEXT.mineEmpty}
            note={context.scope !== 'all' && linkButton(GROUP_TEXT.searchAll, context.onSearchAll)}
            muted
            {...common}
          />
        )
      )}

      {open.count > 0 ? (
        <GroupSection
          group={open}
          title={GROUP_TEXT.open}
          note={
            <>
              {GROUP_TEXT.openNote}
              {visitLine && <> · {visitLine}</>}
            </>
          }
          {...common}
        />
      ) : (
        visitRemoved &&
        visitLine && <p className={styles.visitRemovedLine}>{visitLine}</p>
      )}

      {other.count > 0 && (
        <GroupSection group={other} title={GROUP_TEXT.other} note={GROUP_TEXT.otherNote} {...common} />
      )}
    </>
  )
}
