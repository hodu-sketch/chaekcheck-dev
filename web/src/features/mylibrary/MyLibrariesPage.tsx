import { useQuery } from '@tanstack/react-query'
import { useId, useMemo, useState, type ReactNode } from 'react'
import { ApiError, fetchLibraries } from '../../api/client'
import type { LibraryEligibilityRow } from '../../api/types'
import { BackLink } from '../../components/BackLink'
import { InfoTip } from '../../components/InfoTip'
import { LibraryLogo } from '../../components/LibraryLogo'
import { classificationParts, classificationText } from '../../lib/libraryClassification'
import { useRegions } from '../region/RegionContext'
import { RegionModal } from '../region/RegionModal'
import { useRegionLabels, useRegionTree } from '../region/useRegionTree'
import { findByName, MIN_NAME_QUERY, nameKey } from './libraryNames'
import { useMyLibrary } from './MyLibraryContext'
import { BrowserStorageError, LibraryLimitError } from './myLibraryErrors'
import styles from './MyLibrariesPage.module.css'

/** Wording [안 문구, 규칙 확정] (flow S10), except the two marked dev placeholders. */
const TEXT = {
  limit: '직접 추가는 20곳까지예요. 하나를 빼면 추가할 수 있어요',
  saveFailed: '저장하지 못했어요. 잠시 뒤 다시 해 주세요',
  // dev placeholder (not in flow): the library was switched off or removed meanwhile
  notFound: '지금은 추가할 수 없는 도서관이에요. 목록을 다시 확인해 주세요',
  disabled: '지금은 쓸 수 없는 도서관이에요',
  empty: '지역을 고르거나 도서관을 추가하세요',
  // cycle 07 flow S10 [확정]
  noMatch: '찾는 도서관이 없어요',
  browserBlocked: '이 브라우저에 저장하지 못했어요',
}

/** Name search results shown at a time; more with "더 보기" (cycle 07 flow S10) [확정]. */
const PAGE = 20

/**
 * S10 `/my-libraries` (cycle 06 flow S10): the regions, the libraries they bring in, and the libraries added by hand.
 * Since cycle 07 also before login and with login off (spec R3-6): the same screen, saved in this browser, with no
 * notice line on top [확정]. While the login check runs it says "불러오는 중".
 */
export function MyLibrariesPage() {
  const { mode } = useMyLibrary()
  if (mode === 'waiting') return <p className={styles.page}>불러오는 중</p>
  return <MyLibraries />
}

function MyLibraries() {
  const myLibrary = useMyLibrary()
  const { regions, ready, setRegions, source } = useRegions()
  const tree = useRegionTree()
  const labels = useRegionLabels(tree.data)
  const [editing, setEditing] = useState(false)

  // the public list with the resolved region ids (ADR-0049)
  const list = useQuery({
    queryKey: ['libraries', regions.residence.join(','), regions.work.join(',')],
    queryFn: ({ signal }) => fetchLibraries(regions.residence, regions.work, signal),
    enabled: myLibrary.status === 'ready' && ready,
    staleTime: 5 * 60_000,
  })

  if (myLibrary.status === 'error' || list.isError) {
    return (
      <div className={styles.page}>
        <Heading />
        <p className={styles.state} role="alert">
          내 도서관을 불러오지 못했어요{' '}
          <button
            type="button"
            className={styles.link}
            onClick={() => (myLibrary.status === 'error' ? myLibrary.retry() : void list.refetch())}
          >
            다시 시도
          </button>
        </p>
      </div>
    )
  }
  if (myLibrary.status !== 'ready' || !list.data) {
    return (
      <div className={styles.page}>
        <Heading />
        <p className={styles.state} role="status">
          불러오는 중
        </p>
      </div>
    )
  }

  const rows = list.data
  const empty = regions.residence.length === 0 && regions.work.length === 0 && myLibrary.libraryIds.length === 0
  return (
    <div className={styles.page}>
      <Heading />
      <OpenToAll rows={rows.filter((row) => row.eligibility === 'OPEN')} />
      {empty && <p className={styles.state}>{TEXT.empty}</p>}

      <RegionSection
        residence={regions.residence}
        work={regions.work}
        residenceAuto={rows.filter((row) => row.matchedBy.includes('RESIDENCE'))}
        workAuto={rows.filter((row) => row.matchedBy.includes('WORK'))}
        labels={labels}
        onEdit={() => setEditing(true)}
      />
      <ChosenSections rows={rows} />
      <BackLink />

      <RegionModal
        open={editing}
        onOpenChange={setEditing}
        regions={regions}
        savedTo={source === 'account' ? 'account' : 'browser'}
        onSave={async (next) => {
          await setRegions(next)
          setEditing(false)
        }}
      />
    </div>
  )
}

/** [안 문구] (cycle 08, flow.md 검토 #7): where a choice is kept, shown from the ⓘ next to the title. */
const SAVED_AT_ONCE = {
  browser: '고르는 즉시 이 브라우저에 저장돼요',
  account: '고르는 즉시 계정에 저장돼요',
}

function Heading() {
  const { mode } = useMyLibrary()
  return (
    <header className={styles.head}>
      <h1 className={styles.title}>
        내 도서관
        <InfoTip label="저장 안내" text={mode === 'account' ? SAVED_AT_ONCE.account : SAVED_AT_ONCE.browser} />
      </h1>
      <p className={styles.lead}>"내 도서관" 스위치를 켜면 여기 있는 도서관에서 찾아요.</p>
    </header>
  )
}

function OpenToAll({ rows }: { rows: LibraryEligibilityRow[] }) {
  return (
    <details className={styles.openToAll}>
      <summary>
        {/* phones: a shorter line that does not wrap at 320px (user request 2026-10-05) */}
        <span className={styles.wide}>누구나 가입하는 도서관 {rows.length}곳도 늘 내 도서관에 들어 있어요</span>
        <span className={styles.narrow}>누구나 가입 {rows.length}곳은 늘 포함돼요</span>
      </summary>
      <NameList rows={rows} />
    </details>
  )
}

function Section({ title, aside, children }: { title: string; aside?: ReactNode; children: ReactNode }) {
  const id = useId()
  return (
    <section className={styles.section} aria-labelledby={id}>
      <div className={styles.sectionHead}>
        <h2 id={id} className={styles.sectionTitle}>
          {title}
        </h2>
        {aside}
      </div>
      {children}
    </section>
  )
}

/**
 * "지역 선택": one [변경] for both fields (S2 has its own 거주/직장 tabs), and under each field the libraries it
 * brings in, "거주 지역으로 자동 N곳 ▸" on one line and folded (user requests 2026-10-05, in place of the design's
 * separate sections). Phones put it on the field's line as "자동 N곳 ▸" (user request 2026-10-05). Nothing to remove
 * there (spec R4).
 */
function RegionSection({
  residence,
  work,
  residenceAuto,
  workAuto,
  labels,
  onEdit,
}: {
  residence: number[]
  work: number[]
  residenceAuto: LibraryEligibilityRow[]
  workAuto: LibraryEligibilityRow[]
  labels: Map<number, string>
  onEdit: () => void
}) {
  const names = (ids: number[]) =>
    ids.length === 0 ? '고른 지역이 없어요' : ids.map((id) => labels.get(id) ?? '…').join(', ')
  const regionLabels = { residence: names(residence), work: names(work) }
  return (
    <Section
      title="지역 선택"
      aside={
        <button type="button" className={styles.link} onClick={onEdit} aria-label="지역 선택 변경">
          변경
        </button>
      }
    >
      <div className={styles.regionList}>
        <RegionField kind="거주" regions={regionLabels.residence} rows={residenceAuto} />
        <RegionField kind="직장" regions={regionLabels.work} rows={workAuto} />
      </div>
    </Section>
  )
}

/**
 * One field: "거주 서울 강서구", then "거주 지역으로 자동 N곳 ▸" under it, which opens to the names. On phones the
 * second part sits at the right end of the first line as "자동 N곳 ▸" (user request 2026-10-05); the names open
 * under the line either way.
 */
function RegionField({ kind, regions, rows }: { kind: string; regions: string; rows: LibraryEligibilityRow[] }) {
  const id = useId()
  const [open, setOpen] = useState(false)
  const title = (
    <span id={`${id}-title`} className={styles.autoTitle}>
      {/* the space outside: a trailing space inside would be dropped from the accessible name */}
      <span className={styles.autoKind}>{kind} 지역으로</span> 자동
    </span>
  )
  return (
    <div className={styles.regionField} role="group" aria-labelledby={`${id}-title`}>
      <div className={styles.regionRow}>
        <p className={styles.regionLine}>
          <span className={styles.kind}>{kind}</span>
          <span className={styles.regions}>{regions}</span>
        </p>
        {rows.length === 0 ? (
          <p className={`${styles.muted} ${styles.autoLine}`}>{title} 없어요</p>
        ) : (
          <button
            type="button"
            className={`${styles.autoLine} ${styles.fold}`}
            aria-expanded={open}
            aria-controls={`${id}-names`}
            onClick={() => setOpen((value) => !value)}
          >
            {title} {rows.length}곳
          </button>
        )}
      </div>
      {open && <NameList id={`${id}-names`} rows={rows} />}
    </div>
  )
}

function NameList({ id, rows }: { id?: string; rows: LibraryEligibilityRow[] }) {
  return (
    <ul id={id} className={styles.names}>
      {rows.map((row) => (
        <li key={row.id} className={styles.named}>
          <LibraryLogo name={row.name} logoUrl={row.logoUrl} homepageUrl={row.homepageUrl} size={24} />
          <ClassifiedName row={row} />
        </li>
      ))}
    </ul>
  )
}

/**
 * A library's name with its logo (cycle 07, ADR-0059); the name is text only. A library no longer in the list shows
 * the fallback without a classification (cycle 09).
 */
function LibraryName({ row, fallback }: { row: LibraryEligibilityRow | undefined; fallback: string }) {
  return (
    <span className={`${styles.name} ${styles.named}`}>
      <LibraryLogo name={row?.name ?? fallback} logoUrl={row?.logoUrl} homepageUrl={row?.homepageUrl} size={24} />
      {row ? <ClassifiedName row={row} /> : <span>{fallback}</span>}
    </span>
  )
}

/**
 * Cycle 09 (flow "분류를 붙이는 자리") [확정]: "이름 · 업체 · 대출 방식", next to the name on desktop and tablet, on the
 * line below on phones (CSS). Only the name when the server sent no classification.
 */
function ClassifiedName({ row }: { row: LibraryEligibilityRow }) {
  const classification = classificationText(row)
  return (
    <span>
      <span>{row.name}</span>
      {classification && (
        <span className={styles.classification}>
          <span className={styles.classificationDot}> · </span>
          {classification}
        </span>
      )}
    </span>
  )
}

/** The name and classification for a button label, so two rows of one name differ ("동대문구립도서관 YES24 소장"). */
function spokenName(row: LibraryEligibilityRow): string {
  return [row.name, ...(classificationParts(row) ?? [])].join(' ')
}

/**
 * The last two sections: name search with [추가], and the added libraries N/20 with [빼기]. Every press saves at once:
 * in the account after login, in this browser before (cycle 07).
 */
function ChosenSections({ rows }: { rows: LibraryEligibilityRow[] }) {
  const { libraryIds, maxLibraries, addLibrary, removeLibrary } = useMyLibrary()
  const [query, setQuery] = useState('')
  const [shown, setShown] = useState(PAGE)
  const [message, setMessage] = useState<string | null>(null)
  const byId = useMemo(() => new Map(rows.map((row) => [row.id, row])), [rows])
  const full = libraryIds.length >= maxLibraries
  const searching = [...nameKey(query)].length >= MIN_NAME_QUERY
  const matches = useMemo(() => findByName(rows, query), [rows, query])

  const failure = (error: unknown) => {
    if (error instanceof LibraryLimitError) return TEXT.limit
    if (error instanceof BrowserStorageError) return TEXT.browserBlocked
    if (error instanceof ApiError && error.problem.code === 'library_limit_reached') return TEXT.limit
    if (error instanceof ApiError && error.problem.code === 'library_not_found') return TEXT.notFound
    // 401 after the refresh: the browser is signed out and this screen shows the browser's list
    return TEXT.saveFailed
  }

  const add = async (id: number) => {
    setMessage(null)
    try {
      await addLibrary(id)
    } catch (error) {
      setMessage(failure(error))
    }
  }

  const remove = async (id: number) => {
    setMessage(null)
    try {
      await removeLibrary(id)
    } catch (error) {
      setMessage(failure(error))
    }
  }

  return (
    <>
      <Section title="직접 도서관 추가">
        <input
          type="search"
          className={styles.search}
          value={query}
          onChange={(event) => {
            setQuery(event.target.value)
            setShown(PAGE)
          }}
          placeholder="도서관 이름"
          aria-label="도서관 이름으로 찾기"
        />
        {full && <p className={styles.note}>{TEXT.limit}</p>}
        {searching && matches.length === 0 && <p className={styles.muted}>{TEXT.noMatch}</p>}
        {matches.length > 0 && (
          <ul className={styles.rows}>
            {matches.slice(0, shown).map((row) => {
              const added = libraryIds.includes(row.id)
              return (
                <li key={row.id} className={styles.row} data-added={added || undefined}>
                  <LibraryName row={row} fallback={row.name} />
                  <button
                    type="button"
                    className={styles.add}
                    disabled={added || full}
                    onClick={() => void add(row.id)}
                    aria-label={added ? `${spokenName(row)} 직접 추가함` : `${spokenName(row)} 추가`}
                  >
                    {added ? '직접 추가함' : '추가'}
                  </button>
                </li>
              )
            })}
          </ul>
        )}
        {matches.length > shown && (
          <button type="button" className={styles.more} onClick={() => setShown((n) => n + PAGE)}>
            더 보기
          </button>
        )}
      </Section>

      <Section
        title="직접 추가한 도서관"
        aside={
          <span className={styles.count}>
            {libraryIds.length}/{maxLibraries}
          </span>
        }
      >
        {message && (
          <p className={styles.error} role="alert">
            {message}
          </p>
        )}
        {libraryIds.length === 0 ? (
          <p className={styles.muted}>아직 없어요</p>
        ) : (
          <ul className={styles.rows}>
            {libraryIds.map((id) => {
              const row = byId.get(id)
              return (
                <li key={id} className={styles.row} data-disabled={row ? undefined : true}>
                  <LibraryName row={row} fallback={TEXT.disabled} />
                  <button
                    type="button"
                    className={styles.remove}
                    onClick={() => void remove(id)}
                    aria-label={`${row ? spokenName(row) : '쓸 수 없는 도서관'} 빼기`}
                  >
                    빼기
                  </button>
                </li>
              )
            })}
          </ul>
        )}
      </Section>
    </>
  )
}
