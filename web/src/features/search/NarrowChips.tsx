import { Popover } from 'radix-ui'
import { useId, useState, type FormEvent } from 'react'
import type { BookRole } from '../../api/types'
import { QUERY_MAX_LENGTH } from '../../lib/query'
import { ChevronDown } from '../../components/icons'
import { BOOK_ROLES, FIELD_LABELS, ROLE_LABELS, type BookSearchInput } from './searchCondition'
import { DIRECT_REASON } from './searchSettings'
import styles from './NarrowChips.module.css'

type TextField = 'author' | 'publisher'

/** What a narrowing change did: searched, or the message for the changed field (the window then stays open). */
export type NarrowResult = { done: true } | { done: false; message?: string }

type Props = {
  /** The conditions being edited (the form's state). */
  input: BookSearchInput
  /** The conditions of the search on screen: which chips are shown. */
  searched: BookSearchInput
  /** "도서관에서 직접 찾기": the site search cannot use these, so they are dimmed with the reason. */
  direct: boolean
  /** Searches again at once with the changed conditions. */
  onNarrow: (next: BookSearchInput, field?: TextField) => NarrowResult
}

const PLACEHOLDERS: Record<TextField, string> = { author: '예: 한강', publisher: '예: 창비' }

/**
 * 상세검색 안 C (user 2026-10-19): the first screen has the title box only; the results narrow the search with small
 * chips under the box. 저자 and 출판사 open a small window with a box, 완전 일치 and [적용]; a set value is a filled
 * chip with ×. 제목 정확히 and the author roles (shown once an author is set; they only widen the author match) are
 * toggles. Every change searches again at once, with the same address parameters as before.
 */
export function NarrowChips({ input, searched, direct, onNarrow }: Props) {
  const toggleExactTitle = () =>
    onNarrow({
      ...input,
      exact: input.exact.includes('title') ? input.exact.filter((f) => f !== 'title') : [...input.exact, 'title'],
    })
  const toggleRole = (role: BookRole) =>
    onNarrow({
      ...input,
      roles: input.roles.includes(role) ? input.roles.filter((r) => r !== role) : [...input.roles, role],
    })
  const titleExact = input.exact.includes('title')

  return (
    <div className={styles.chips} role="group" aria-label="검색 좁히기" data-direct={direct || undefined}>
      <FieldChip field="author" input={input} disabled={direct} onNarrow={onNarrow} />
      <FieldChip field="publisher" input={input} disabled={direct} onNarrow={onNarrow} />
      {searched.title.trim() && (
        <button
          type="button"
          className={styles.chip}
          aria-pressed={titleExact}
          data-on={titleExact || undefined}
          disabled={direct}
          onClick={toggleExactTitle}
        >
          제목 정확히
        </button>
      )}
      {searched.author.trim() &&
        BOOK_ROLES.map((role) => {
          const on = input.roles.includes(role)
          return (
            <button
              key={role}
              type="button"
              className={styles.chip}
              aria-pressed={on}
              data-on={on || undefined}
              disabled={direct}
              onClick={() => toggleRole(role)}
            >
              {ROLE_LABELS[role]} 포함
            </button>
          )
        })}
      {direct && <p className={styles.reason}>{DIRECT_REASON}</p>}
    </div>
  )
}

/** 저자 / 출판사: "저자 ▾" opens the small window; set, "저자: 한강" (opens it again) and ×. */
function FieldChip({
  field,
  input,
  disabled,
  onNarrow,
}: {
  field: TextField
  input: BookSearchInput
  disabled: boolean
  onNarrow: Props['onNarrow']
}) {
  const id = useId()
  const label = FIELD_LABELS[field]
  const value = input[field].trim()
  const exact = input.exact.includes(field)
  const [open, setOpen] = useState(false)
  const [draft, setDraft] = useState(value)
  const [draftExact, setDraftExact] = useState(exact)
  const [message, setMessage] = useState<string | undefined>()

  const changeOpen = (next: boolean) => {
    if (next) {
      setDraft(input[field])
      setDraftExact(exact)
      setMessage(undefined)
    }
    setOpen(next)
  }

  /** The value and its 완전 일치 with this field changed; an author's roles go with the author. */
  const withField = (text: string, on: boolean): BookSearchInput => ({
    ...input,
    [field]: text,
    exact: on && text.trim() ? [...input.exact.filter((f) => f !== field), field] : input.exact.filter((f) => f !== field),
    roles: field === 'author' && !text.trim() ? [] : input.roles,
  })

  const apply = (event: FormEvent) => {
    event.preventDefault()
    // the window is in a portal: React would pass the submit on to the search form
    event.stopPropagation()
    const result = onNarrow(withField(draft, draftExact), field)
    if (result.done || !result.message) setOpen(false)
    else setMessage(result.message)
  }

  const clear = () => onNarrow(withField('', false), field)

  return (
    <Popover.Root open={open} onOpenChange={changeOpen}>
      <Popover.Anchor asChild>
        <span className={styles.fieldChip} data-on={value ? true : undefined}>
          <Popover.Trigger className={styles.chipMain} disabled={disabled}>
            {value ? (
              <>
                {label}: {value}
                {exact && <span className={styles.exactMark}> · 완전 일치</span>}
              </>
            ) : (
              <>
                {label}
                <ChevronDown size={9} className={styles.caret} />
              </>
            )}
          </Popover.Trigger>
          {value && (
            <button type="button" className={styles.clear} aria-label={`${label} 지우기`} disabled={disabled} onClick={clear}>
              <span aria-hidden="true">×</span>
            </button>
          )}
        </span>
      </Popover.Anchor>
      <Popover.Portal>
        <Popover.Content
          className={styles.window}
          side="bottom"
          align="start"
          sideOffset={6}
          collisionPadding={12}
          aria-label={`${label}로 좁히기`}
        >
          <form className={styles.windowForm} onSubmit={apply} noValidate>
            <label htmlFor={`${id}-box`} className={styles.windowLabel}>
              {label}
            </label>
            <div className={styles.windowLine}>
              <input
                id={`${id}-box`}
                type="search"
                className={styles.windowInput}
                value={draft}
                maxLength={QUERY_MAX_LENGTH}
                placeholder={PLACEHOLDERS[field]}
                autoComplete="off"
                aria-invalid={message ? true : undefined}
                aria-describedby={message ? `${id}-error` : undefined}
                onChange={(event) => {
                  setDraft(event.target.value)
                  setMessage(undefined)
                }}
              />
              <button type="submit" className={styles.apply}>
                적용
              </button>
            </div>
            <label className={styles.check}>
              <input type="checkbox" checked={draftExact} onChange={(event) => setDraftExact(event.target.checked)} />
              완전 일치
            </label>
            {message && (
              <p id={`${id}-error`} className={styles.error} role="alert">
                {message}
              </p>
            )}
          </form>
        </Popover.Content>
      </Popover.Portal>
    </Popover.Root>
  )
}
