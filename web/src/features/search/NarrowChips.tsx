import { Dialog, Popover } from 'radix-ui'
import { useId, useState, type FormEvent } from 'react'
import type { BookRole } from '../../api/types'
import { QUERY_MAX_LENGTH } from '../../lib/query'
import { ChevronDown } from '../../components/icons'
import { useMediaQuery } from '../../lib/useMediaQuery'
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
  /** /search before a search: 제목 정확히 is there from the start, and a change only sets the condition. */
  beforeSearch?: boolean
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
export function NarrowChips({ input, searched, direct, beforeSearch = false, onNarrow }: Props) {
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
      {(beforeSearch || searched.title.trim()) && (
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
  const phone = useMediaQuery('(width <= 640px)')

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

  const chipText = value ? (
    <>
      {label}: {value}
      {exact && <span className={styles.exactMark}> · 완전 일치</span>}
    </>
  ) : (
    <>
      {label}
      <ChevronDown size={9} className={styles.caret} />
    </>
  )
  const clearButton = value && (
    <button type="button" className={styles.clear} aria-label={`${label} 지우기`} disabled={disabled} onClick={clear}>
      <span aria-hidden="true">×</span>
    </button>
  )
  const box = (
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
  )
  const applyButton = (
    <button type="submit" className={styles.apply}>
      적용
    </button>
  )
  const exactBox = (
    <label className={styles.check}>
      <input type="checkbox" checked={draftExact} onChange={(event) => setDraftExact(event.target.checked)} />
      완전 일치
    </label>
  )
  const error = message && (
    <p id={`${id}-error`} className={styles.error} role="alert">
      {message}
    </p>
  )
  const nameLabel = (
    <label htmlFor={`${id}-box`} className={styles.windowLabel}>
      {label}
    </label>
  )
  // wider screens: the box and 적용 on one line; phones: one thing per row, 적용 last and full width (user 2026-10-20:
  // "검색칸 좀 수습해줘 이상해" — the box was squashed on the user's phone)
  const body = phone ? (
    <form className={`${styles.windowForm} ${styles.stacked}`} onSubmit={apply} noValidate>
      {nameLabel}
      {box}
      {exactBox}
      {error}
      {applyButton}
    </form>
  ) : (
    <form className={styles.windowForm} onSubmit={apply} noValidate>
      {nameLabel}
      <div className={styles.windowLine}>
        {box}
        {applyButton}
      </div>
      {exactBox}
      {error}
    </form>
  )

  // phones: a window in the middle of the screen (user 2026-10-20: "저자 출판사 검색 모바일에서 삑사리난거 수습해",
  // then "저자 출판사 이거 가운데창 모달로 폰에서는"; the small window ran off the screen)
  if (phone) {
    return (
      <>
        <span className={styles.fieldChip} data-on={value ? true : undefined}>
          <button
            type="button"
            className={styles.chipMain}
            disabled={disabled}
            aria-haspopup="dialog"
            onClick={() => changeOpen(true)}
          >
            {chipText}
          </button>
          {clearButton}
        </span>
        <Dialog.Root open={open} onOpenChange={changeOpen}>
          <Dialog.Portal>
            <Dialog.Overlay className={styles.modalOverlay} />
            <Dialog.Content className={styles.modal} aria-describedby={undefined}>
              <Dialog.Title className="visually-hidden">{`${label}로 좁히기`}</Dialog.Title>
              <Dialog.Close className={styles.modalClose} aria-label="닫기">
                <span aria-hidden="true">×</span>
              </Dialog.Close>
              {body}
            </Dialog.Content>
          </Dialog.Portal>
        </Dialog.Root>
      </>
    )
  }

  return (
    <Popover.Root open={open} onOpenChange={changeOpen}>
      <Popover.Anchor asChild>
        <span className={styles.fieldChip} data-on={value ? true : undefined}>
          <Popover.Trigger className={styles.chipMain} disabled={disabled}>
            {chipText}
          </Popover.Trigger>
          {clearButton}
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
          {body}
        </Popover.Content>
      </Popover.Portal>
    </Popover.Root>
  )
}
