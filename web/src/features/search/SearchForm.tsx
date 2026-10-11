import { useId, useState, type FormEvent, type ReactNode } from 'react'
import type { BookRole, SearchField } from '../../api/types'
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
import { SettingsPanel } from './SettingsPanel'
import styles from './SearchForm.module.css'
import { ChevronDown, ChevronUp, MenuLines } from '../../components/icons'

const MESSAGES: Record<Exclude<QueryProblem, null>, string> = {
  too_short: `${QUERY_MIN_LENGTH}글자 이상 입력하세요`,
  // the inputs refuse more than 50 characters; this shows only for a longer text in the address
  too_long: `${QUERY_MAX_LENGTH}자 이하로 입력하세요`,
}

type Problems = Partial<Record<SearchField | 'none', string>>

type Props = {
  /** Conditions from the address, or the empty form. */
  initial: BookSearchInput
  settings: SearchSettings
  /** Results are shown: changing exact match or roles then searches again at once [안]. */
  hasResults: boolean
  onSearch: (input: BookSearchInput) => void
  onSettingsChange: (settings: SearchSettings) => void
  /** Phones: the "찾을 도서관" section for the settings panel, and whether it differs from the default. */
  settingsScope?: ReactNode
  /** Phones: what "찾을 도서관" is set to, on its row in the settings sheet. */
  settingsScopeValue?: string
  settingsMarked?: boolean
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
  onSearch,
  onSettingsChange,
  settingsScope,
  settingsScopeValue,
  settingsMarked,
  lineEnd,
}: Props) {
  const id = useId()
  const [input, setInput] = useState<BookSearchInput>(initial)
  // checks alone (from the address) do not open 상세검색 before a search
  const [expanded, setExpanded] = useState(() =>
    Boolean(initial.author || initial.publisher || (hasResults && (initial.exact.length || initial.roles.length))),
  )
  const [problems, setProblems] = useState<Problems>(() => addressProblems(initial))
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
    if (Object.keys(found).length === 0) onSearch(normalized)
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
    <form className={styles.form} data-start={hasResults ? undefined : true} onSubmit={handleSubmit} role="search" noValidate>
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
          scopeSection={settingsScope}
          scopeValue={settingsScopeValue}
          marked={settingsMarked}
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
        </div>
      )}
    </form>
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
