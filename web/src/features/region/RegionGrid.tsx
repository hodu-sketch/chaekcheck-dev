import { useState } from 'react'
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
  /**
   * One region only (the main page's box, user 2026-10-20): no + while one is chosen and no limit line; × first to pick
   * another.
   */
  single?: boolean
}

/**
 * The region picker without a dialog (cycle 22 R12; on every width since user 2026-10-19): the chosen regions as chips in
 * a row ending in a small +, which shows a 시도 and a 시군구 select. The tree is read with `useRegionTree` (fetched once per
 * visit, R12-3).
 */
export function RegionGrid({ field, value, onToggle, full, notices = [], single = false }: Props) {
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
        {!(single && value.length > 0) && (
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
        )}
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
        {full && !single && <p className={styles.limit}>최대 {MAX_REGIONS_PER_FIELD}곳이에요</p>}
      </div>
    </div>
  )
}

/**
 * Shown after the chips' + is pressed: a 시도 select and that 시도's 시군구 select side by side.
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

/**
 * One region picked straight in two selects, no chip and no + (the main page's box, user 2026-10-21 design A: "「+」
 * 단계를 없애고 시·도 / 시·군·구 칸을 처음부터 보여 줌. 고른 뒤에도 같은 칸에서 바로 바꿈"). The 시군구 select shows the
 * chosen one; picking another replaces it.
 */
export function RegionSelect({
  field,
  value,
  onPick,
  notices = [],
}: {
  field: RegionField
  /** The chosen SIGUNGU id, or null. */
  value: number | null
  onPick: (id: number) => void
  notices?: RegionNotice[]
}) {
  const tree = useRegionTree()
  const [sidoId, setSidoId] = useState<number | null>(null)
  const fieldLabel = FIELD_LABEL[field]
  const valueSido = tree.data?.find((s) => s.children.some((c) => c.id === value)) ?? null
  const active = tree.data?.find((s) => s.id === sidoId) ?? valueSido ?? tree.data?.[0] ?? null
  const shown = active !== null && active.children.some((c) => c.id === value) ? String(value) : ''

  return (
    <div className={styles.root}>
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
        {tree.data && active && (
          <>
            <select
              className={styles.select}
              aria-label={`${fieldLabel} 시도`}
              value={active.id}
              onChange={(e) => setSidoId(Number(e.target.value))}
            >
              {tree.data.map((sido) => (
                <option key={sido.id} value={sido.id}>
                  {sido.shortName ?? sido.name}
                </option>
              ))}
            </select>
            <select
              className={styles.select}
              aria-label={`${fieldLabel} 시군구`}
              value={shown}
              data-picked={shown !== '' || undefined}
              onChange={(e) => {
                if (e.target.value) onPick(Number(e.target.value))
              }}
            >
              <option value="" disabled={shown !== ''}>
                시·군·구 고르기
              </option>
              {active.children.map((sigungu) => (
                <option key={sigungu.id} value={sigungu.id}>
                  {sigungu.name}
                </option>
              ))}
            </select>
          </>
        )}
      </div>
      {notices.length > 0 && (
        <ul className={styles.notices} role="status">
          {notices.map((notice) => (
            <li key={notice.message}>{notice.message}</li>
          ))}
        </ul>
      )}
    </div>
  )
}
