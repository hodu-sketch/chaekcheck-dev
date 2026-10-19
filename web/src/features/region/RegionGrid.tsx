import { useEffect, useId, useRef, useState } from 'react'
import type { SidoNode } from '../../api/types'
import { Cross } from '../../components/icons'
import type { RegionNotice } from './applyResolutions'
import { MAX_REGIONS_PER_FIELD, type StoredRegions } from './regionStorage'
import { useRegionLabels, useRegionTree } from './useRegionTree'
import styles from './RegionGrid.module.css'

export type RegionField = keyof StoredRegions

const FIELD_LABEL: Record<RegionField, string> = { residence: '사는 곳', work: '일하는 곳' }

interface Props {
  /** Which of the user's regions this grid edits (one grid per field, cycle 22 R12). */
  field: RegionField
  /** The SIGUNGU ids chosen for that field. */
  value: number[]
  /** Called with a SIGUNGU id to add (a 시군구 picked) or remove it (a chip's ×). Saving is the caller's (no save button). */
  onToggle: (id: number) => void
  /** The limit is reached: + is disabled and "최대 20곳이에요" shows. */
  full: boolean
  /** Resolution notes (`/regions/resolutions`, cycle 06), shown under the chips. */
  notices?: RegionNotice[]
}

/**
 * The region picker without a dialog (cycle 22 R12; on every width since user 2026-10-19): the chosen regions as chips in
 * a row ending in a small +, which shows a 시도 and a 시군구 select. The tree is read with `useRegionTree` (fetched once per
 * visit, R12-3).
 */
export function RegionGrid({ field, value, onToggle, full, notices = [] }: Props) {
  const tree = useRegionTree()
  const labels = useRegionLabels(tree.data)
  const [sidoId, setSidoId] = useState<number | null>(null)
  const fieldLabel = FIELD_LABEL[field]
  // the selects show only after + is pressed (user 2026-10-19)
  const [adding, setAdding] = useState(false)
  const selectsOpen = adding && !full

  // first open: the 시도 of the first chosen region, else the first one; never a 시군구 picked for the user (flow #5)
  const initialSido = tree.data?.find((s) => s.children.some((c) => c.id === value[0])) ?? tree.data?.[0] ?? null
  const activeSido = tree.data?.find((s) => s.id === sidoId) ?? initialSido

  return (
    <div className={styles.root}>
      <div className={styles.chipRow}>
        {value.length > 0 && (
          <ul className={styles.chips} aria-label={`고른 ${fieldLabel}`}>
            {value.map((id) => {
              const label = labels.get(id) ?? `지역 ${id}`
              return (
                <li key={id} className={styles.chip}>
                  {label}
                  <button
                    type="button"
                    className={styles.chipRemove}
                    onClick={() => onToggle(id)}
                    aria-label={`${label} 빼기`}
                  >
                    <Cross size={10} />
                  </button>
                </li>
              )
            })}
          </ul>
        )}
        {/* + shows the selects, × (while open) hides them without adding */}
        <button
          type="button"
          className={styles.add}
          aria-label={`${fieldLabel} 추가`}
          aria-expanded={selectsOpen}
          disabled={full || !tree.data}
          onClick={() => setAdding((open) => !open)}
        >
          {selectsOpen ? <Cross size={10} /> : <Plus />}
        </button>
      </div>
      {notices.length > 0 && (
        <ul className={styles.notices} role="status">
          {notices.map((notice) => (
            <li key={notice.message}>{notice.message}</li>
          ))}
        </ul>
      )}

      <div className={styles.selects}>
        {tree.isPending && <p className={styles.status}>불러오는 중…</p>}
        {tree.isError && (
          <p className={styles.status} role="alert">
            지역 목록을 불러오지 못했어요{' '}
            <button type="button" className={styles.linkButton} onClick={() => tree.refetch()}>
              다시 시도
            </button>
          </p>
        )}
        {tree.data && activeSido && selectsOpen && (
          <SelectPicker
            fieldLabel={fieldLabel}
            sidos={tree.data}
            active={activeSido}
            chosen={value}
            onSelectSido={setSidoId}
            onPick={(id) => {
              onToggle(id)
              setAdding(false)
            }}
          />
        )}
        {full && <p className={styles.limit}>최대 {MAX_REGIONS_PER_FIELD}곳이에요</p>}
      </div>
    </div>
  )
}

/**
 * Shown after the chips' + is pressed: a 시도 and that 시도's 시군구 dropdown side by side. Picking a 시군구 adds it at once
 * and the dropdowns hide again; chosen ones carry a leading ✓ and cannot be picked (removing is the chip ×'s job).
 */
function SelectPicker({
  fieldLabel,
  sidos,
  active,
  chosen,
  onSelectSido,
  onPick,
}: {
  fieldLabel: string
  sidos: SidoNode[]
  active: SidoNode
  chosen: number[]
  onSelectSido: (id: number) => void
  onPick: (id: number) => void
}) {
  return (
    <>
      <PickList
        label={`${fieldLabel} 시도`}
        shown={active.shortName ?? active.name}
        options={sidos.map((sido) => ({ id: sido.id, text: sido.shortName ?? sido.name, selected: sido.id === active.id }))}
        onPick={onSelectSido}
      />
      <PickList
        label={`${fieldLabel} 시군구`}
        shown="시군구 고르기"
        placeholder
        options={active.children.map((sigungu) => {
          const checked = chosen.includes(sigungu.id)
          return { id: sigungu.id, text: checked ? `✓ ${sigungu.name}` : sigungu.name, disabled: checked }
        })}
        onPick={onPick}
      />
    </>
  )
}

interface PickOption {
  id: number
  text: string
  selected?: boolean
  disabled?: boolean
}

/**
 * A dropdown drawn by the page instead of the browser's select (user 2026-10-20: on the phone the select's own list
 * moved after a tap and needed its 완료 button; "안 누르고 되도록 하고싶어, 완료 버튼이 따로 없어야 해"): the same box with
 * the violet chevron opens a list under it, and one tap on a line picks it and closes the list. A tap outside or Esc
 * closes it without picking.
 */
function PickList({
  label,
  shown,
  placeholder = false,
  options,
  onPick,
}: {
  label: string
  shown: string
  placeholder?: boolean
  options: PickOption[]
  onPick: (id: number) => void
}) {
  const [open, setOpen] = useState(false)
  const rootRef = useRef<HTMLDivElement>(null)
  const listRef = useRef<HTMLDivElement>(null)
  const listId = useId()

  useEffect(() => {
    if (!open) return
    const outside = (event: PointerEvent) => {
      if (!rootRef.current?.contains(event.target as Node)) setOpen(false)
    }
    const escape = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        // only the list closes, not the window around it
        event.stopPropagation()
        setOpen(false)
      }
    }
    document.addEventListener('pointerdown', outside)
    document.addEventListener('keydown', escape, true)
    // the chosen line in the middle of the list, without moving the page
    const list = listRef.current
    const selected = list?.querySelector<HTMLElement>('[aria-selected="true"]')
    if (list && selected) list.scrollTop = selected.offsetTop - (list.clientHeight - selected.offsetHeight) / 2
    return () => {
      document.removeEventListener('pointerdown', outside)
      document.removeEventListener('keydown', escape, true)
    }
  }, [open])

  return (
    <div className={styles.pick} ref={rootRef}>
      <button
        type="button"
        className={styles.select}
        role="combobox"
        aria-label={label}
        aria-expanded={open}
        aria-haspopup="listbox"
        aria-controls={open ? listId : undefined}
        data-placeholder={placeholder || undefined}
        onClick={() => setOpen((value) => !value)}
      >
        {shown}
      </button>
      {open && (
        <div id={listId} ref={listRef} className={styles.list} role="listbox" aria-label={label}>
          {options.map((option) => (
            <button
              key={option.id}
              type="button"
              role="option"
              className={styles.option}
              aria-selected={option.selected ?? false}
              disabled={option.disabled}
              onClick={() => {
                setOpen(false)
                onPick(option.id)
              }}
            >
              {option.text}
            </button>
          ))}
        </div>
      )}
    </div>
  )
}

/** The chips' + (drawn like components/icons, which has no plus). */
function Plus() {
  return (
    <svg
      width={10}
      height={10}
      viewBox="0 0 12 12"
      fill="none"
      stroke="currentColor"
      strokeWidth={2}
      strokeLinecap="round"
      aria-hidden
      focusable={false}
    >
      <path d="M6 1.5v9M1.5 6h9" />
    </svg>
  )
}
