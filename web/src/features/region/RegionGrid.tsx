import { useState } from 'react'
import type { SidoNode } from '../../api/types'
import { Cross } from '../../components/icons'
import { useMediaQuery } from '../../lib/useMediaQuery'
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
  /** Called with a SIGUNGU id to add or remove it (a cell, or a chip's ×). Saving is the caller's (no save button). */
  onToggle: (id: number) => void
  /** The limit is reached: the unchecked cells are disabled and "최대 20곳이에요" shows. */
  full: boolean
  /** Resolution notes (`/regions/resolutions`, cycle 06), shown under the chips. */
  notices?: RegionNotice[]
}

/**
 * The region grid without a dialog (cycle 22 R12, flow "지역 격자 부품"): 시도 on the left with a count badge, the
 * chosen 시도's 시군구 as check cells on the right, the chosen regions as chips above. The tree is read with
 * `useRegionTree` (fetched once per visit, R12-3).
 */
export function RegionGrid({ field, value, onToggle, full, notices = [] }: Props) {
  const tree = useRegionTree()
  const labels = useRegionLabels(tree.data)
  const [sidoId, setSidoId] = useState<number | null>(null)
  const fieldLabel = FIELD_LABEL[field]
  // phones: two native select boxes instead of the grid (user 2026-10-19); only one of the two renders
  const phone = useMediaQuery('(width <= 640px)')
  // phones: the selects show only after + is pressed (user 2026-10-19)
  const [adding, setAdding] = useState(false)
  const selectsOpen = phone && adding && !full

  // first open: the 시도 of the first chosen region, else the first one; never a 시군구 picked for the user (flow #5)
  const initialSido = tree.data?.find((s) => s.children.some((c) => c.id === value[0])) ?? tree.data?.[0] ?? null
  const activeSido = tree.data?.find((s) => s.id === sidoId) ?? initialSido

  const chips = value.length > 0 && (
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
              <Cross size={phone ? 10 : 12} />
            </button>
          </li>
        )
      })}
    </ul>
  )

  return (
    <div className={styles.root} data-phone={phone || undefined}>
      {phone ? (
        <div className={styles.chipRow}>
          {chips}
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
      ) : (
        chips
      )}
      {notices.length > 0 && (
        <ul className={styles.notices} role="status">
          {notices.map((notice) => (
            <li key={notice.message}>{notice.message}</li>
          ))}
        </ul>
      )}

      <div className={phone ? styles.selects : styles.picker}>
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
        {phone && full && <p className={styles.limit}>최대 {MAX_REGIONS_PER_FIELD}곳이에요</p>}
        {tree.data && activeSido && !phone && (
          <>
            <SidoList
              label={`${fieldLabel} 시도`}
              sidos={tree.data}
              activeId={activeSido.id}
              chosen={value}
              onSelect={setSidoId}
            />
            <fieldset className={styles.sigunguSet}>
              <legend className={styles.sigunguLegend}>
                {activeSido.name}
                {full && <span className={styles.limit}>최대 {MAX_REGIONS_PER_FIELD}곳이에요</span>}
              </legend>
              <div className={styles.sigunguGrid}>
                {activeSido.children.map((sigungu) => {
                  const checked = value.includes(sigungu.id)
                  return (
                    <label
                      key={sigungu.id}
                      className={styles.sigungu}
                      data-checked={checked || undefined}
                      // narrow grids: a name over four letters takes the whole row, so none of them wraps
                      data-long={sigungu.name.length > 4 || undefined}
                    >
                      <input
                        type="checkbox"
                        checked={checked}
                        disabled={!checked && full}
                        onChange={() => onToggle(sigungu.id)}
                      />
                      {sigungu.name}
                    </label>
                  )
                })}
              </div>
            </fieldset>
          </>
        )}
      </div>
    </div>
  )
}

/**
 * Phones (user 2026-10-19), shown after the chips' + is pressed: a 시도 select and that 시도's 시군구 select side by side.
 * Picking a 시군구 adds it at once and the selects hide again; chosen ones carry a leading ✓ and cannot be picked (removing
 * is the chip ×'s job).
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
      <select
        className={styles.select}
        aria-label={`${fieldLabel} 시도`}
        value={active.id}
        onChange={(e) => onSelectSido(Number(e.target.value))}
      >
        {sidos.map((sido) => (
          <option key={sido.id} value={sido.id}>
            {sido.shortName ?? sido.name}
          </option>
        ))}
      </select>
      <select
        className={styles.select}
        aria-label={`${fieldLabel} 시군구`}
        value=""
        onChange={(e) => {
          if (e.target.value) onPick(Number(e.target.value))
        }}
      >
        <option value="">시군구 고르기</option>
        {active.children.map((sigungu) => {
          const checked = chosen.includes(sigungu.id)
          return (
            <option key={sigungu.id} value={sigungu.id} disabled={checked}>
              {checked ? `✓ ${sigungu.name}` : sigungu.name}
            </option>
          )
        })}
      </select>
    </>
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

function SidoList({
  label,
  sidos,
  activeId,
  chosen,
  onSelect,
}: {
  label: string
  sidos: SidoNode[]
  activeId: number
  chosen: number[]
  onSelect: (id: number) => void
}) {
  return (
    <ul className={styles.sidoList} aria-label={label}>
      {sidos.map((sido) => {
        const count = sido.children.filter((c) => chosen.includes(c.id)).length
        return (
          <li key={sido.id}>
            <button
              type="button"
              className={styles.sido}
              aria-pressed={sido.id === activeId}
              onClick={() => onSelect(sido.id)}
            >
              {sido.shortName ?? sido.name}
              {count > 0 && <span className={styles.sidoCount}>{count}</span>}
            </button>
          </li>
        )
      })}
    </ul>
  )
}
