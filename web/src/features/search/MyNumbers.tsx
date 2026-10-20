import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { fetchLibraries } from '../../api/client'
import type { LibraryEligibilityRow } from '../../api/types'
import { useRegionLabels, useRegionTree } from '../region/useRegionTree'
import { regionPlaceText, ro } from './regionPlace'
import styles from './MyNumbers.module.css'

/** Names shown under the pills before 「이름 보기」 [안: two]. */
const NAMES_SHOWN = 2

/** Names added per 「더 보기」 in the opened list, as the my page's list (10). */
const NAMES_STEP = 10

/** [확정] (ADR-0139, handoff 5-A-4). */
export const NUMBERS_TEXT = {
  headline: (place: string, total: number) => `${place} → 가입할 수 있는 도서관 ${total}곳`,
  byRegion: (sido: string | null, basis: string, count: number) => `${sido ? `${sido} ` : ''}${basis}${ro(basis)} ${count}`,
  open: (count: number) => `어디 살든 ${count}`,
  visit: (count: number) => `그중 가야 하는 곳 ${count}`,
  showNames: '이름 보기',
  more: (count: number) => `${count}곳 더 보기`,
  // user 2026-10-20: "줄이기도 좀 있어줘"
  fold: '접기',
} as const

export interface Counted {
  eligible: LibraryEligibilityRow[]
  open: LibraryEligibilityRow[]
  visit: number
}

/** ELIGIBLE = joined by the regions, OPEN = 어디 살든; `visitRequired` is already for the regions sent. */
export function countRows(rows: readonly LibraryEligibilityRow[]): Counted {
  const eligible = rows.filter((row) => row.eligibility === 'ELIGIBLE')
  const open = rows.filter((row) => row.eligibility === 'OPEN')
  const visit = [...eligible, ...open].filter((row) => row.visitRequired).length
  return { eligible, open, visit }
}

/** "거주" / "재직" / "거주·재직": the sides the regions are on (the words of ADR-0139 "거주 · 재직 · 상관없이"). */
export function basisWord(residence: readonly number[], work: readonly number[]): string {
  if (residence.length > 0 && work.length > 0) return '거주·재직'
  return work.length > 0 ? '재직' : '거주'
}

/**
 * The person's own numbers right after they pick a region (user 2026-10-20, ADR-0139 #4, handoff 5-A-4): "서울 강서구 →
 * 가입할 수 있는 도서관 70곳" and the pills [서울 거주로 32] [어디 살든 38] [그중 가야 하는 곳 9], two names and
 * 「이름 보기」. The list is asked once for these regions and kept 5 minutes under the my page's key; the libraries'
 * own 시군구 is not in the answer, so the region side is one number ("서울 거주로"). Nothing shows while it loads or
 * when it fails.
 */
export function MyNumbers({ residence, work }: { residence: readonly number[]; work: readonly number[] }) {
  const residenceKey = residence.join(',')
  const workKey = work.join(',')
  const has = residence.length + work.length > 0
  const list = useQuery({
    queryKey: ['libraries', residenceKey, workKey],
    queryFn: ({ signal }) => fetchLibraries([...residence], [...work], signal),
    enabled: has,
    staleTime: 5 * 60_000,
  })
  const tree = useRegionTree()
  const labels = useRegionLabels(tree.data)
  const [shown, setShown] = useState(0)
  const side = residence.length > 0 ? residence : work
  const place = regionPlaceText(side, labels)
  if (!has || !list.data || place === null) return null

  const counted = countRows(list.data)
  const total = counted.eligible.length + counted.open.length
  const sidos = new Set(side.map((id) => labels.get(id)?.split(' ')[0]))
  const sido = sidos.size === 1 ? ([...sidos][0] ?? null) : null
  const names = [...counted.eligible, ...counted.open].map((row) => row.name)
  return (
    <section className={styles.panel} aria-label={NUMBERS_TEXT.headline(place, total)}>
      <p className={styles.headline}>{NUMBERS_TEXT.headline(place, total)}</p>
      <p className={styles.pills}>
        <span className={styles.pill} data-tone="mine">
          {NUMBERS_TEXT.byRegion(sido, basisWord(residence, work), counted.eligible.length)}
        </span>
        <span className={styles.pill} data-tone="open">
          {NUMBERS_TEXT.open(counted.open.length)}
        </span>
        {counted.visit > 0 && (
          <span className={styles.pill} data-tone="visit">
            {NUMBERS_TEXT.visit(counted.visit)}
          </span>
        )}
      </p>
      {shown === 0 ? (
        <p className={styles.names}>
          {names.slice(0, NAMES_SHOWN).join(', ')}
          {names.length > NAMES_SHOWN && (
            <>
              {' … '}
              <button type="button" className={styles.link} onClick={() => setShown(NAMES_STEP)}>
                {NUMBERS_TEXT.showNames} ▾
              </button>
            </>
          )}
        </p>
      ) : (
        <>
          <ul className={styles.list}>
            {names.slice(0, shown).map((name, index) => (
              // two libraries may have the same name (a second site)
              <li key={`${index}-${name}`}>{name}</li>
            ))}
          </ul>
          <p className={styles.actions}>
            {names.length > shown && (
              <button type="button" className={styles.link} onClick={() => setShown(shown + NAMES_STEP)}>
                {NUMBERS_TEXT.more(Math.min(NAMES_STEP, names.length - shown))}
              </button>
            )}
            <button type="button" className={styles.link} onClick={() => setShown(0)}>
              {NUMBERS_TEXT.fold} ▴
            </button>
          </p>
        </>
      )}
    </section>
  )
}
