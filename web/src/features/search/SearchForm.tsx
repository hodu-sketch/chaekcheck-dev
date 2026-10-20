import { useId, useState, type FormEvent, type ReactNode } from 'react'
import type { SearchField } from '../../api/types'
import { checkQuery, normalizeQuery, QUERY_MAX_LENGTH, QUERY_MIN_LENGTH, type QueryProblem } from '../../lib/query'
import { NarrowChips, type NarrowResult } from './NarrowChips'
import { EMPTY_INPUT, FIELD_LABELS, filledFields, type BookSearchInput } from './searchCondition'
import type { SearchSettings } from './searchSettings'
import { SettingsPanel } from './SettingsPanel'
import styles from './SearchForm.module.css'

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
  /** Results are shown: the narrowing chips are there, and each change searches again at once [안]. */
  hasResults: boolean
  /**
   * The first screen (`/` before a search): the box takes its column's width, with a filled violet [검색]; `/search`
   * before a search keeps the results' layout.
   */
  centered?: boolean
  onSearch: (input: BookSearchInput) => void
  onSettingsChange: (settings: SearchSettings) => void
  /** Phones: the settings open as a bottom sheet (without "찾을 도서관", which is the chip's own sheet). */
  settingsSheet?: boolean
  /** Phones: the "내 도서관" chip, on its own line right under the title box (cycle 22 R2-1). */
  chip?: ReactNode
  /** With results: the 「온라인 가입만」 chip's state and its switch (user 2026-10-20). */
  onlineOnly?: { on: boolean; onToggle: () => void }
  /** Wider screens: the condition card, between the title box and the narrowing chips as on phones (user 2026-10-20). */
  beforeChips?: ReactNode
  /** Phones: the sort, at the right end of the settings line (user request 2026-10-05). */
  lineEnd?: ReactNode
}

/**
 * S1 search area (flow.md cycle 04; 상세검색 안 C, user 2026-10-19): one title box with [검색] and ⚙ for S4. The first
 * screen has the box only; /search has the chips from the start (they set conditions for [검색]); with results the chips narrow the search (author, publisher, 완전 일치, author
 * roles) and search again at once. No condition dropdown [확정]. Empty fields are left out; at least one is needed;
 * each filled field must be 2 to 50 characters (cycle 01 rule). With "도서관에서 직접 찾기" on, the chips are dimmed
 * with a short reason, and only the first filled field is searched.
 */
export function SearchForm({
  initial,
  settings,
  hasResults,
  onlineOnly,
  centered = !hasResults,
  onSearch,
  onSettingsChange,
  settingsSheet = false,
  chip,
  beforeChips,
  lineEnd,
}: Props) {
  const id = useId()
  const [input, setInput] = useState<BookSearchInput>(initial)
  const [problems, setProblems] = useState<Problems>(() => addressProblems(initial))
  const direct = settings.direct

  const normalize = (next: BookSearchInput): BookSearchInput => ({
    ...next,
    title: normalizeQuery(next.title),
    author: direct ? '' : normalizeQuery(next.author),
    publisher: direct ? '' : normalizeQuery(next.publisher),
  })

  const handleSubmit = (event: FormEvent) => {
    event.preventDefault()
    const normalized = normalize(input)
    const found = check(normalized)
    setProblems(found)
    if (Object.keys(found).length === 0) onSearch(normalized)
  }

  const setTitle = (text: string) => {
    setInput((current) => ({ ...current, title: text }))
    if (problems.title || problems.none) setProblems({})
  }

  // a chip change searches again at once [안]; a problem of the changed field is shown in its small window. Before a
  // search (/search, user 2026-10-19) a chip only sets the condition; [검색] searches with it
  const narrow = (next: BookSearchInput, field?: SearchField): NarrowResult => {
    const normalized = normalize(next)
    if (!hasResults) {
      const problem = field && normalized[field] ? checkQuery(normalized[field]) : null
      if (problem) return { done: false, message: MESSAGES[problem] }
      setInput(next)
      return { done: true }
    }
    const found = check(normalized)
    setProblems(found)
    if (Object.keys(found).length > 0) return { done: false, message: field ? found[field] : undefined }
    setInput(next)
    onSearch(normalized)
    return { done: true }
  }

  const errorId = (field: SearchField | 'none') => `${id}-${field}-error`
  // with results the author and publisher problems are shown in their chip's window; before, under the box
  const shownProblems = (['title', 'author', 'publisher'] as const).filter(
    (field) => problems[field] && (field === 'title' || !hasResults),
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
            onChange={(event) => setTitle(event.target.value)}
          />
          <button type="submit" className={styles.submit}>
            검색
          </button>
        </div>
        {chip && <div className={styles.chipSlot}>{chip}</div>}
        {/* none on the first screen (user 2026-10-19: "메인에서는 그냥 검색 설정도 빼자") */}
        {!centered && <SettingsPanel settings={settings} onChange={onSettingsChange} sheet={settingsSheet} />}
        {lineEnd && <div className={styles.lineEnd}>{lineEnd}</div>}
      </div>
      {/* not on screen, only the red box (user 2026-10-20: "검색어를 입력하세요 문구 빼줘"); screen readers still hear it */}
      {problems.none && (
        <p id={errorId('none')} className="visually-hidden" role="alert">
          {problems.none}
        </p>
      )}
      {shownProblems.map((field) => (
        <p key={field} id={errorId(field)} className={styles.error} role="alert">
          {field === 'title' ? problems[field] : `${FIELD_LABELS[field]}: ${problems[field]}`}
        </p>
      ))}
      {beforeChips}
      {/* on /search from the start (user 2026-10-19: "이 검색 칸에는 처음부터 저자 출판사 제목 정확히가"); not on the first screen */}
      {!centered && (
        <NarrowChips
          input={input}
          searched={initial}
          direct={direct}
          beforeSearch={!hasResults}
          onNarrow={narrow}
          onlineOnly={onlineOnly}
        />
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
