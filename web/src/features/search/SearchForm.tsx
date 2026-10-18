import { useId, useMemo, useState, type FormEvent, type ReactNode } from 'react'
import type { BookRole, SearchField } from '../../api/types'
import { classificationParts, classificationText } from '../../lib/libraryClassification'
import { checkQuery, normalizeQuery, QUERY_MAX_LENGTH, QUERY_MIN_LENGTH, type QueryProblem } from '../../lib/query'
import {
  BOOK_ROLES,
  EMPTY_INPUT,
  FIELD_LABELS,
  filledFields,
  ROLE_LABELS,
  type BookSearchInput,
} from './searchCondition'
import { DIRECT_REASON, type SearchSettings } from './searchSettings'
import { SCOPE_TEXT } from './LibraryScopeLine'
import { SettingsPanel } from './SettingsPanel'
import { useLibraryList } from './useLibraryList'
import { findByName, MIN_NAME_QUERY, nameKey } from '../mylibrary/libraryNames'
import { useMyLibrary } from '../mylibrary/MyLibraryContext'
import { LIBRARY_CHANGE_TEXT, libraryChangeMessage } from '../mylibrary/myLibraryErrors'
import { SaveHint } from '../mylibrary/SaveHint'
import { REGION_SAVE_FAILED, useRegions } from '../region/RegionContext'
import { RegionGrid, type RegionField } from '../region/RegionGrid'
import { MAX_REGIONS_PER_FIELD, type StoredRegions } from '../region/regionStorage'
import styles from './SearchForm.module.css'
import { ChevronDown, ChevronUp, Cross, MenuLines } from '../../components/icons'

const MESSAGES: Record<Exclude<QueryProblem, null>, string> = {
  too_short: `${QUERY_MIN_LENGTH}글자 이상 입력하세요`,
  // the inputs refuse more than 50 characters; this shows only for a longer text in the address
  too_long: `${QUERY_MAX_LENGTH}자 이하로 입력하세요`,
}

/**
 * The texts of the 상세검색 rows for the user's regions, libraries and the 전국 box (cycle 22 문구표 "상세검색", R10)
 * [확정], except the marked dev placeholders [안].
 */
export const ADVANCED_TEXT = {
  residence: '사는 곳',
  work: '일하는 곳',
  addWork: '일하는 곳도 더하기',
  library: '도서관',
  nameSearch: '도서관 이름으로 찾기',
  add: '더하기',
  // the my page's words for a library already added [안]
  added: '직접 추가함',
  chosen: (count: number, max: number) => `직접 추가 ${count}/${max}`,
  // cycle 07 flow S10 [확정]
  noMatch: '찾는 도서관이 없어요',
  loading: '불러오는 중…',
  // dev placeholder, like the region grid's "지역 목록을 불러오지 못했어요" [안]
  listFailed: '도서관 목록을 불러오지 못했어요',
  retry: '다시 시도',
  more: '더 보기',
  scope: '범위',
  all: '전국에서 찾기 · 이번 검색만',
} as const

/** How [검색] searches besides the conditions: `all` = the 상세검색 "전국에서 찾기" box was checked (D10). */
export interface SearchOptions {
  all: boolean
}

type Problems = Partial<Record<SearchField | 'none', string>>

type Props = {
  /** Conditions from the address, or the empty form. */
  initial: BookSearchInput
  settings: SearchSettings
  /** Results are shown: changing exact match or roles then searches again at once [안]. */
  hasResults: boolean
  /** The centred first screen (`/` before a search); `/search` before a search keeps the results' layout. */
  centered?: boolean
  onSearch: (input: BookSearchInput, options: SearchOptions) => void
  onSettingsChange: (settings: SearchSettings) => void
  /** Phones: the settings open as a bottom sheet (without "찾을 도서관", which is the chip's own sheet). */
  settingsSheet?: boolean
  /** Wider screens: the 방문 가입 빼기 switch in the settings window (cycle 22 R8-1). */
  settingsVisit?: ReactNode
  /** Phones: the "내 도서관" chip, on its own line right under the title box (cycle 22 R2-1). */
  chip?: ReactNode
  /** Phones: the sort, at the right end of the "상세검색 · 설정" line (user request 2026-10-05). */
  lineEnd?: ReactNode
}

/**
 * S1 search area (flow.md cycle 04): one title box with [검색], "상세검색" for author and publisher, a "완전 일치"
 * check per field, author roles, and ⚙ for S4. No condition dropdown [확정]. Empty fields are left out; at least one
 * is needed; each filled field must be 2 to 50 characters (cycle 01 rule). With "도서관에서 직접 찾기" on, what the
 * site search cannot use is dimmed with a short reason, and only the first filled field is searched.
 */
export function SearchForm({
  initial,
  settings,
  hasResults,
  centered = !hasResults,
  onSearch,
  onSettingsChange,
  settingsSheet = false,
  settingsVisit,
  chip,
  lineEnd,
}: Props) {
  const id = useId()
  const [input, setInput] = useState<BookSearchInput>(initial)
  // checks alone (from the address) do not open 상세검색 before a search
  const [expanded, setExpanded] = useState(() =>
    Boolean(initial.author || initial.publisher || (hasResults && (initial.exact.length || initial.roles.length))),
  )
  const [problems, setProblems] = useState<Problems>(() => addressProblems(initial))
  // "전국에서 찾기 · 이번 검색만" (R4-2 ③, D10): never stored, cleared after the search it went with
  const [all, setAll] = useState(false)
  const direct = settings.direct

  const trySearch = (next: BookSearchInput) => {
    const normalized: BookSearchInput = {
      ...next,
      title: normalizeQuery(next.title),
      author: direct ? '' : normalizeQuery(next.author),
      publisher: direct ? '' : normalizeQuery(next.publisher),
    }
    const found = check(normalized)
    setProblems(found)
    if (Object.keys(found).length > 0) return
    // the box of a closed panel does not count (it cannot be seen)
    onSearch(normalized, { all: expanded && all })
    setAll(false)
  }

  const handleSubmit = (event: FormEvent) => {
    event.preventDefault()
    trySearch(input)
  }

  const setText = (field: SearchField, text: string) => {
    setInput((current) => ({ ...current, [field]: text }))
    if (problems[field] || problems.none) setProblems({})
  }

  // with results a changed option searches again right away [안]; before a search nothing is sent [안]
  const setOption = (next: BookSearchInput) => {
    setInput(next)
    if (hasResults) trySearch(next)
  }

  const toggleExact = (field: SearchField, on: boolean) =>
    setOption({ ...input, exact: on ? [...input.exact, field] : input.exact.filter((f) => f !== field) })
  const toggleRole = (role: BookRole, on: boolean) =>
    setOption({ ...input, roles: on ? [...input.roles, role] : input.roles.filter((r) => r !== role) })

  const errorId = (field: SearchField | 'none') => `${id}-${field}-error`
  const fieldError = (field: SearchField) =>
    problems[field] && (
      <p id={errorId(field)} className={styles.error} role="alert">
        {problems[field]}
      </p>
    )
  const exactBox = (field: SearchField) => (
    <label className={styles.check}>
      <input
        type="checkbox"
        checked={input.exact.includes(field)}
        disabled={direct}
        onChange={(event) => toggleExact(field, event.target.checked)}
      />
      완전 일치<span className="visually-hidden"> ({FIELD_LABELS[field]})</span>
    </label>
  )

  return (
    <form className={styles.form} data-start={centered ? true : undefined} onSubmit={handleSubmit} role="search" noValidate>
      <div className={styles.topLine}>
        <div className={styles.field} data-invalid={problems.title || problems.none ? true : undefined}>
          <label htmlFor={`${id}-title`} className="visually-hidden">
            책 제목
          </label>
          <input
            id={`${id}-title`}
            name="title"
            type="search"
            className={styles.input}
            value={input.title}
            maxLength={QUERY_MAX_LENGTH}
            placeholder="책 제목을 입력하세요"
            autoComplete="off"
            aria-invalid={problems.title ? true : undefined}
            aria-describedby={problems.title ? errorId('title') : problems.none ? errorId('none') : undefined}
            onChange={(event) => setText('title', event.target.value)}
          />
          <button type="submit" className={styles.submit}>
            검색
          </button>
        </div>
        {chip && <div className={styles.chipSlot}>{chip}</div>}
        <button
          type="button"
          className={styles.moreButton}
          aria-expanded={expanded}
          aria-controls={`${id}-more`}
          aria-label="상세검색"
          onClick={() => setExpanded((open) => !open)}
        >
          <span className={styles.moreIcon} aria-hidden="true">
            <MenuLines />
          </span>
          <span className={styles.moreLabel}>상세검색</span>
          <span className={styles.caret} aria-hidden="true">
            {expanded ? <ChevronUp size={10} /> : <ChevronDown size={10} />}
          </span>
        </button>
        <SettingsPanel
          settings={settings}
          onChange={onSettingsChange}
          sheet={settingsSheet}
          visitSwitch={settingsVisit}
        />
        {lineEnd && <div className={styles.lineEnd}>{lineEnd}</div>}
      </div>
      {problems.none && (
        <p id={errorId('none')} className={styles.error} role="alert">
          {problems.none}
        </p>
      )}
      {fieldError('title')}

      {expanded && (
        <div id={`${id}-more`} className={styles.more} data-direct={direct || undefined}>
          {direct && <p className={styles.reason}>{DIRECT_REASON}</p>}
          <div className={styles.row}>
            <span className={styles.rowLabel}>제목</span>
            {exactBox('title')}
          </div>
          <div className={styles.row}>
            <label htmlFor={`${id}-author`} className={styles.rowLabel}>
              저자
            </label>
            <input
              id={`${id}-author`}
              name="author"
              type="search"
              className={styles.smallInput}
              value={input.author}
              maxLength={QUERY_MAX_LENGTH}
              disabled={direct}
              autoComplete="off"
              aria-invalid={problems.author ? true : undefined}
              aria-describedby={problems.author ? errorId('author') : undefined}
              onChange={(event) => setText('author', event.target.value)}
            />
            {exactBox('author')}
            <span className={styles.roles} role="group" aria-label="저자 역할 더하기">
              {BOOK_ROLES.map((role) => (
                <label key={role} className={styles.check}>
                  <input
                    type="checkbox"
                    checked={input.roles.includes(role)}
                    disabled={direct}
                    onChange={(event) => toggleRole(role, event.target.checked)}
                  />
                  {ROLE_LABELS[role]}
                </label>
              ))}
            </span>
          </div>
          {fieldError('author')}
          <div className={styles.row}>
            <label htmlFor={`${id}-publisher`} className={styles.rowLabel}>
              출판사
            </label>
            <input
              id={`${id}-publisher`}
              name="publisher"
              type="search"
              className={styles.smallInput}
              value={input.publisher}
              maxLength={QUERY_MAX_LENGTH}
              disabled={direct}
              autoComplete="off"
              aria-invalid={problems.publisher ? true : undefined}
              aria-describedby={problems.publisher ? errorId('publisher') : undefined}
              onChange={(event) => setText('publisher', event.target.value)}
            />
            {exactBox('publisher')}
          </div>
          {fieldError('publisher')}
          <RegionRows all={all} onAllChange={setAll} />
        </div>
      )}
    </form>
  )
}

/**
 * Cycle 22 R10 (flow S1-adv): under 출판사, "사는 곳" and "일하는 곳" (chips + the region grid, as in the box; 일하는 곳
 * behind [일하는 곳도 더하기] until there is one), "도서관" (name search, [더하기], the added ones) and "범위" with the
 * 전국 box. A region or library change only saves (R10-2, AC-20): never a search.
 */
function RegionRows({ all, onAllChange }: { all: boolean; onAllChange: (all: boolean) => void }) {
  const id = useId()
  const { regions, setRegions, source, notices, saveFailed } = useRegions()
  const [workOpen, setWorkOpen] = useState(false)
  const [saved, setSaved] = useState<RegionField | null>(null)

  const toggle = (field: RegionField, regionId: number) => {
    const current = regions[field]
    const has = current.includes(regionId)
    if (!has && current.length >= MAX_REGIONS_PER_FIELD) return
    const next: StoredRegions = {
      ...regions,
      [field]: has ? current.filter((x) => x !== regionId) : [...current, regionId],
    }
    // a failure is on screen (saveFailed)
    setRegions(next).catch(() => undefined)
    setSaved(source === 'browser' ? field : null)
  }

  const grid = (field: RegionField) => (
    <RegionGrid
      field={field}
      value={regions[field]}
      onToggle={(regionId) => toggle(field, regionId)}
      full={regions[field].length >= MAX_REGIONS_PER_FIELD}
      notices={field === 'residence' ? notices : undefined}
    />
  )

  return (
    <div className={styles.mine}>
      <div className={styles.mineRow} role="group" aria-labelledby={`${id}-residence`}>
        <span id={`${id}-residence`} className={styles.mineLabel}>
          {ADVANCED_TEXT.residence}
        </span>
        <div className={styles.mineBody}>
          {grid('residence')}
          <SaveHint saved={saved === 'residence'} />
        </div>
      </div>
      <div className={styles.mineRow} role="group" aria-labelledby={`${id}-work`}>
        <span id={`${id}-work`} className={styles.mineLabel}>
          {ADVANCED_TEXT.work}
        </span>
        <div className={styles.mineBody}>
          {workOpen || regions.work.length > 0 ? (
            grid('work')
          ) : (
            <button type="button" className={styles.addWork} onClick={() => setWorkOpen(true)}>
              {ADVANCED_TEXT.addWork}
            </button>
          )}
          <SaveHint saved={saved === 'work'} />
        </div>
      </div>
      {saveFailed && (
        <p className={styles.mineError} role="alert">
          {REGION_SAVE_FAILED}
        </p>
      )}
      <div className={styles.mineRow} role="group" aria-labelledby={`${id}-library`}>
        <span id={`${id}-library`} className={styles.mineLabel}>
          {ADVANCED_TEXT.library}
        </span>
        <div className={styles.mineBody}>
          <LibraryNameRow />
        </div>
      </div>
      <div className={styles.mineRow}>
        <span className={styles.mineLabel} aria-hidden="true">
          {ADVANCED_TEXT.scope}
        </span>
        <div className={styles.mineBody}>
          <label className={styles.mineCheck}>
            <input type="checkbox" checked={all} onChange={(event) => onAllChange(event.target.checked)} />
            {ADVANCED_TEXT.all}
          </label>
        </div>
      </div>
    </div>
  )
}

/** Libraries found by name shown at a time, more with "더 보기" (the my page's 10, R10-3). */
const NAME_PAGE = 10

/**
 * The "도서관" row (R10-1, R10-3): the my page's name search (`findByName`: NFC, no spaces, a part of the name; ADR-0049,
 * 0058) over the whole list read once when needed (D4), found rows 10 at a time with [더하기], then "직접 추가 N/20"
 * with the added names as chips with ×. Each change saves at once in `MyLibraryContext` (the account after login,
 * this browser before); a failure says `libraryChangeMessage`.
 */
function LibraryNameRow() {
  const { mode, libraryIds, maxLibraries, addLibrary, removeLibrary } = useMyLibrary()
  const [query, setQuery] = useState('')
  const [shown, setShown] = useState(NAME_PAGE)
  const [message, setMessage] = useState<string | null>(null)
  const [saved, setSaved] = useState(false)
  const searching = [...nameKey(query)].length >= MIN_NAME_QUERY
  // D4: nothing is asked without added libraries until a name is typed
  const list = useLibraryList(searching || libraryIds.length > 0)
  const rows = list.data
  const matches = useMemo(() => (rows && searching ? findByName(rows, query) : []), [rows, query, searching])
  const byId = useMemo(() => new Map((rows ?? []).map((row) => [row.id, row])), [rows])
  const full = libraryIds.length >= maxLibraries

  const change = async (action: () => Promise<void>) => {
    setMessage(null)
    try {
      await action()
      setSaved(mode === 'browser')
    } catch (error) {
      setMessage(libraryChangeMessage(error))
    }
  }

  return (
    <>
      <input
        type="search"
        className={styles.nameInput}
        value={query}
        placeholder={ADVANCED_TEXT.nameSearch}
        aria-label={ADVANCED_TEXT.nameSearch}
        autoComplete="off"
        onChange={(event) => {
          setQuery(event.target.value)
          setShown(NAME_PAGE)
        }}
        // Enter finds nothing more here; it must not send the book search of the form around it
        onKeyDown={(event) => {
          if (event.key === 'Enter') event.preventDefault()
        }}
      />
      {searching && list.isPending && <p className={styles.mineNote}>{ADVANCED_TEXT.loading}</p>}
      {searching && list.isError && (
        <p className={styles.mineError} role="alert">
          {ADVANCED_TEXT.listFailed}{' '}
          <button type="button" className={styles.nameLink} onClick={() => list.refetch()}>
            {ADVANCED_TEXT.retry}
          </button>
        </p>
      )}
      {searching && rows && matches.length === 0 && <p className={styles.mineNote}>{ADVANCED_TEXT.noMatch}</p>}
      {matches.length > 0 && (
        <ul className={styles.found} aria-label="찾은 도서관">
          {matches.slice(0, shown).map((row) => {
            const added = libraryIds.includes(row.id)
            const kind = classificationText(row)
            const spoken = [row.name, ...(classificationParts(row) ?? [])].join(' ')
            return (
              <li key={row.id} className={styles.foundRow}>
                <span className={styles.foundName}>
                  {row.name}
                  {kind && <span className={styles.foundKind}> · {kind}</span>}
                </span>
                <button
                  type="button"
                  className={styles.addButton}
                  disabled={added || full}
                  onClick={() => void change(() => addLibrary(row.id))}
                  aria-label={`${spoken} ${added ? ADVANCED_TEXT.added : ADVANCED_TEXT.add}`}
                >
                  {added ? ADVANCED_TEXT.added : ADVANCED_TEXT.add}
                </button>
              </li>
            )
          })}
        </ul>
      )}
      {matches.length > shown && (
        <button type="button" className={styles.moreNames} onClick={() => setShown((n) => n + NAME_PAGE)}>
          {ADVANCED_TEXT.more}
        </button>
      )}
      {full && <p className={styles.mineNote}>{LIBRARY_CHANGE_TEXT.limit}</p>}
      {message && (
        <p className={styles.mineError} role="alert">
          {message}
        </p>
      )}
      <div className={styles.chosenLine}>
        <p className={styles.chosenCount}>{ADVANCED_TEXT.chosen(libraryIds.length, maxLibraries)}</p>
        {libraryIds.length > 0 && (
          <ul className={styles.chosenChips} aria-label="직접 추가한 도서관">
            {libraryIds.map((libraryId) => {
              const name = byId.get(libraryId)?.name ?? (rows ? SCOPE_TEXT.disabled : '…')
              return (
                <li key={libraryId} className={styles.chosenChip}>
                  {name}
                  <button
                    type="button"
                    className={styles.chosenRemove}
                    onClick={() => void change(() => removeLibrary(libraryId))}
                    aria-label={`${name} 빼기`}
                  >
                    <Cross size={10} />
                  </button>
                </li>
              )
            })}
          </ul>
        )}
      </div>
      <SaveHint saved={saved} />
    </>
  )
}

/** No field: "검색어를 입력하세요" [안]; each filled field 2 to 50 characters (cycle 01 wording). */
function check(input: BookSearchInput): Problems {
  const filled = filledFields(input)
  if (filled.length === 0) return { none: '검색어를 입력하세요' }
  const problems: Problems = {}
  for (const field of filled) {
    const problem = checkQuery(input[field])
    if (problem) problems[field] = MESSAGES[problem]
  }
  return problems
}

/** A too short or too long text that came in through the address. */
function addressProblems(input: BookSearchInput): Problems {
  if (input === EMPTY_INPUT || filledFields(input).length === 0) return {}
  return check({
    ...input,
    title: normalizeQuery(input.title),
    author: normalizeQuery(input.author),
    publisher: normalizeQuery(input.publisher),
  })
}
