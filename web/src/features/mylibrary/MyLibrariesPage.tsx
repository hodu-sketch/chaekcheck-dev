import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { useEffect, useId, useMemo, useState, type ReactNode } from 'react'
import { fetchLibraries } from '../../api/client'
import type { LibraryEligibilityRow } from '../../api/types'
import { BackLink } from '../../components/BackLink'
import { ChevronDown, ChevronUp } from '../../components/icons'
import { InfoTip } from '../../components/InfoTip'
import { LibraryLogo } from '../../components/LibraryLogo'
import { classificationParts, classificationText } from '../../lib/libraryClassification'
import type { RegionNotice } from '../region/applyResolutions'
import { REGION_SAVE_FAILED, useRegions } from '../region/RegionContext'
import { RegionGrid, type RegionField as GridField } from '../region/RegionGrid'
import { MAX_REGIONS_PER_FIELD, type StoredRegions } from '../region/regionStorage'
import { savePrefs } from '../search/searchSettings'
import { AccountSection } from '../account/AccountSection'
import { findByName, MIN_NAME_QUERY, nameKey } from './libraryNames'
import { useMyLibrary } from './MyLibraryContext'
import { LIBRARY_CHANGE_TEXT, libraryChangeMessage } from './myLibraryErrors'
import { SaveHint } from './SaveHint'
import styles from './MyLibrariesPage.module.css'

/** Wording [안 문구, 규칙 확정] (flow S10); a failed add or remove says `libraryChangeMessage`. */
const TEXT = {
  limit: LIBRARY_CHANGE_TEXT.limit,
  disabled: '지금은 쓸 수 없는 도서관이에요',
  empty: '지역을 고르거나 도서관을 추가하세요',
  // cycle 07 flow S10 [확정]
  noMatch: '찾는 도서관이 없어요',
}

/**
 * Libraries shown at a time in every long list here (name search results, 누구나 가입, the regions' libraries); more
 * with "더 보기". 20 in cycle 07 (flow S10); 10 since the user's request of 2026-10-14 ("최대 10개까지 뜨고 그 이후에
 * 더보기", too many at once).
 */
const PAGE = 10

/**
 * S10 `/my-libraries`, titled 마이페이지 (user 2026-10-19: 내 도서관 관리 and 계정 are one page): the regions, the
 * libraries they bring in and the libraries added by hand first, then the account part, with 탈퇴하기 far below
 * everything. Since cycle 07 the library part also works before login and with login off (spec R3-6): the same
 * screen, saved in this browser, with no notice line on top [확정]; the account part asks to log in then. While the
 * login check runs the library part says "불러오는 중".
 */
export function MyLibrariesPage() {
  const { mode } = useMyLibrary()
  return (
    <div className={styles.page}>
      <Heading />
      {mode === 'waiting' ? (
        <p className={styles.state} role="status">
          불러오는 중
        </p>
      ) : (
        <MyLibraries />
      )}
      <AccountSection beforeWithdraw={<BackLink />} />
    </div>
  )
}

function MyLibraries() {
  const myLibrary = useMyLibrary()
  const { regions, ready, setRegions, source, notices, saveFailed } = useRegions()
  /** A check was saved in this browser on this visit: the save hint may show (cycle 22 R9). */
  const [guestSaved, setGuestSaved] = useState(false)

  // the public list with the resolved region ids (ADR-0049), asked again after every check (cycle 22 D6); the last
  // answer stays on screen meanwhile so the grid is not replaced by "불러오는 중"
  const list = useQuery({
    queryKey: ['libraries', regions.residence.join(','), regions.work.join(',')],
    queryFn: ({ signal }) => fetchLibraries(regions.residence, regions.work, signal),
    enabled: myLibrary.status === 'ready' && ready,
    staleTime: 5 * 60_000,
    placeholderData: keepPreviousData,
  })
  // the number of 어디 살든 가입 libraries, for the search screen's box (cycle 22 R3-4, D5 openCount): only from the list
  // without regions, as a region rule that matches makes a nationwide library ELIGIBLE instead
  const withoutRegions = regions.residence.length === 0 && regions.work.length === 0 && !list.isPlaceholderData
  const openCount = withoutRegions ? list.data?.filter((row) => row.eligibility === 'OPEN').length : undefined
  useEffect(() => {
    if (openCount !== undefined) savePrefs({ openCount })
  }, [openCount])

  /** A grid check or a chip's ×: saved at once, no search (cycle 22 D6, R11-1). */
  const toggle = (field: GridField, id: number) => {
    const current = regions[field]
    const has = current.includes(id)
    if (!has && current.length >= MAX_REGIONS_PER_FIELD) return
    const next: StoredRegions = { ...regions, [field]: has ? current.filter((x) => x !== id) : [...current, id] }
    // a failure is on screen (saveFailed), nothing else to do here
    setRegions(next).catch(() => undefined)
    if (source === 'browser') setGuestSaved(true)
  }

  if (myLibrary.status === 'error' || list.isError) {
    return (
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
    )
  }
  if (myLibrary.status !== 'ready' || !list.data) {
    return (
      <p className={styles.state} role="status">
        불러오는 중
      </p>
    )
  }

  const rows = list.data
  const empty = regions.residence.length === 0 && regions.work.length === 0 && myLibrary.libraryIds.length === 0
  return (
    <>
      <OpenToAll rows={rows.filter((row) => row.eligibility === 'OPEN')} />
      {empty && <p className={styles.state}>{TEXT.empty}</p>}

      <RegionSection
        regions={regions}
        notices={notices}
        saveFailed={saveFailed}
        guestSaved={guestSaved}
        onToggle={toggle}
        residenceAuto={rows.filter((row) => row.matchedBy.includes('RESIDENCE'))}
        workAuto={rows.filter((row) => row.matchedBy.includes('WORK'))}
      />
      <ChosenSections rows={rows} />
    </>
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
        마이페이지
        <InfoTip label="저장 안내" text={mode === 'account' ? SAVED_AT_ONCE.account : SAVED_AT_ONCE.browser} />
      </h1>
      {/* cycle 22 R11-2: there is no scope switch any more (ADR-0135) */}
      <p className={styles.lead}>검색하면 여기 있는 도서관에서 먼저 찾아요</p>
    </header>
  )
}

function OpenToAll({ rows }: { rows: LibraryEligibilityRow[] }) {
  return (
    <details className={styles.openToAll}>
      <summary>
        {/* phones: a shorter line that does not wrap at 320px (user request 2026-10-05); cycle 22 R11-2 wording */}
        {/* no counts on this page (user 2026-10-19: "거주 1곳 직장 0곳 이런거 뜨는데 그냥 개수 다 없애줘") */}
        <span className={styles.wide}>어디 살든 가입되는 도서관도 늘 내 도서관에 들어 있어요</span>
        <span className={styles.narrow}>어디 살든 가입 도서관은 늘 포함돼요</span>
        {/* the same chevron as the header and the search form, down when it opens downward, up while open (user
            2026-10-17: "아래로 내려서 보여주는거니 아래향 화살표로 … 화살표랑 꺽쇠 섞여있는데") */}
        <span className={styles.caret} aria-hidden="true">
          <span className={styles.caretDown}>
            <ChevronDown size={10} />
          </span>
          <span className={styles.caretUp}>
            <ChevronUp size={10} />
          </span>
        </span>
      </summary>
      <NameList rows={rows} />
    </details>
  )
}

function Section({
  title,
  aside,
  className,
  children,
}: {
  title: string
  aside?: ReactNode
  className?: string
  children: ReactNode
}) {
  const id = useId()
  return (
    <section className={className ? `${styles.section} ${className}` : styles.section} aria-labelledby={id}>
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
 * "지역 선택" (cycle 22 R11-1, flow S10): no [변경] and no dialog any more; a "사는 곳" row and an "일하는 곳" row, each
 * the chips and the grid (`RegionGrid`), every check saved at once. The 일하는 곳 grid stays folded behind
 * [일하는 곳도 더하기] until it is opened or has a region (flow "지역 격자 부품"). Under each grid the libraries it brings
 * in, "거주 ▾", folded (user requests 2026-10-05). Nothing to remove there (spec R4). A failed save
 * and, before login, the save hint (R9) under the rows.
 */
function RegionSection({
  regions,
  notices,
  saveFailed,
  guestSaved,
  onToggle,
  residenceAuto,
  workAuto,
}: {
  regions: StoredRegions
  notices: RegionNotice[]
  saveFailed: boolean
  guestSaved: boolean
  onToggle: (field: GridField, id: number) => void
  residenceAuto: LibraryEligibilityRow[]
  workAuto: LibraryEligibilityRow[]
}) {
  const [workOpen, setWorkOpen] = useState(false)
  const showWork = workOpen || regions.work.length > 0
  return (
    <Section title="지역 선택" className={styles.regionSection}>
      <div className={styles.regionList}>
        <RegionRow
          title="사는 곳"
          field="residence"
          value={regions.residence}
          notices={notices}
          onToggle={onToggle}
          kind="거주"
          rows={residenceAuto}
        />
        {showWork ? (
          <RegionRow
            title="일하는 곳"
            field="work"
            value={regions.work}
            onToggle={(field, id) => {
              // stays open when the last one is removed
              setWorkOpen(true)
              onToggle(field, id)
            }}
            kind="직장"
            rows={workAuto}
          />
        ) : (
          <div className={styles.regionBlock}>
            <h3 className={styles.blockTitle}>일하는 곳</h3>
            <button type="button" className={styles.addWork} onClick={() => setWorkOpen(true)}>
              일하는 곳도 더하기
            </button>
          </div>
        )}
      </div>
      {saveFailed && (
        <p className={styles.error} role="alert">
          {REGION_SAVE_FAILED}
        </p>
      )}
      <SaveHint saved={guestSaved} />
    </Section>
  )
}

/** One field: its title, the grid (chips on top), then "거주 ▾". */
function RegionRow({
  title,
  field,
  value,
  notices,
  onToggle,
  kind,
  rows,
}: {
  title: string
  field: GridField
  value: number[]
  notices?: RegionNotice[]
  onToggle: (field: GridField, id: number) => void
  kind: string
  rows: LibraryEligibilityRow[]
}) {
  const id = useId()
  return (
    <div className={styles.regionBlock} role="group" aria-labelledby={id}>
      <h3 id={id} className={styles.blockTitle}>
        {title}
      </h3>
      <div className={styles.gridBleed}>
        <RegionGrid
          field={field}
          value={value}
          onToggle={(regionId) => onToggle(field, regionId)}
          full={value.length >= MAX_REGIONS_PER_FIELD}
          notices={notices}
        />
      </div>
      <RegionField kind={kind} rows={rows} />
    </div>
  )
}

/**
 * The libraries one field brings in: "거주 ▾", which opens to the names (user request 2026-10-05); no count and no
 * line when the field brings none (user 2026-10-19: "개수 다 없애줘").
 */
function RegionField({ kind, rows }: { kind: string; rows: LibraryEligibilityRow[] }) {
  const id = useId()
  const [open, setOpen] = useState(false)
  const title = (
    <span id={`${id}-title`} className={styles.autoTitle}>
      {/* just "거주" / "직장" (user 2026-10-19: "지역으로 자동 이런 말 빼줘") */}
      <span className={styles.autoKind}>{kind}</span>
    </span>
  )
  if (rows.length === 0) return null
  return (
    <div className={styles.regionField} role="group" aria-labelledby={`${id}-title`}>
      <button
        type="button"
        className={`${styles.autoLine} ${styles.fold}`}
        aria-expanded={open}
        aria-controls={`${id}-names`}
        onClick={() => setOpen((value) => !value)}
      >
        {title}
        <span className={styles.caret} aria-hidden="true">
          {open ? <ChevronUp size={10} /> : <ChevronDown size={10} />}
        </span>
      </button>
      {open && <NameList id={`${id}-names`} rows={rows} />}
    </div>
  )
}

function NameList({ id, rows }: { id?: string; rows: LibraryEligibilityRow[] }) {
  const [shown, setShown] = useState(PAGE)
  return (
    <>
      <ul id={id} className={styles.names}>
        {rows.slice(0, shown).map((row) => (
          <li key={row.id} className={styles.named}>
            <LibraryLogo
              name={row.name}
              logoUrl={row.logoUrl}
              size={24}
            />
            <ClassifiedName row={row} />
          </li>
        ))}
      </ul>
      {rows.length > shown && (
        <button type="button" className={styles.more} onClick={() => setShown((n) => n + PAGE)}>
          더 보기
        </button>
      )}
    </>
  )
}

/**
 * A library's name with its logo (cycle 07, ADR-0059); the name is text only. A library no longer in the list shows
 * the fallback without a classification (cycle 09).
 */
function LibraryName({ row, fallback }: { row: LibraryEligibilityRow | undefined; fallback: string }) {
  return (
    <span className={`${styles.name} ${styles.named}`}>
      <LibraryLogo
        name={row?.name ?? fallback}
        logoUrl={row?.logoUrl}
        size={24}
      />
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

  const add = async (id: number) => {
    setMessage(null)
    try {
      await addLibrary(id)
    } catch (error) {
      setMessage(libraryChangeMessage(error))
    }
  }

  const remove = async (id: number) => {
    setMessage(null)
    try {
      await removeLibrary(id)
    } catch (error) {
      setMessage(libraryChangeMessage(error))
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
