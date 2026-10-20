import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { fetchLibraries } from '../../api/client'
import type { LibraryEligibilityRow, RegionBasis } from '../../api/types'
import { useRegionLabels, useRegionTree } from '../region/useRegionTree'
import { regionPlaceText, ro } from './regionPlace'
import styles from './MyNumbers.module.css'

/** Names shown under the pills before 「이름 보기」 [안: two]. */
const NAMES_SHOWN = 2

/** Names added per 「더 보기」 in the opened list, as the my page's list (10). */
const NAMES_STEP = 10

/** [확정] (ADR-0139, handoff 5-A-4). */
export const NUMBERS_TEXT = {
  headline: (place: string, total: number) => `${place} → 빌릴 수 있는 도서관 ${total}곳`,
  byRegion: (sido: string | null, basis: string, count: number) => `${sido ? `${sido} ` : ''}${basis}${ro(basis)} ${count}`,
  open: (count: number) => `어디 살든 ${count}`,
  showNames: '이름 보기',
  more: (count: number) => `${count}곳 더 보기`,
  // user 2026-10-20: "줄이기도 좀 있어줘"
  fold: '접기',
} as const

export interface Counted {
  eligible: LibraryEligibilityRow[]
  open: LibraryEligibilityRow[]
}

/** ELIGIBLE = joined by the regions, OPEN = 어디 살든 (no 방문 가입 count, user 2026-10-20: "아예 빼줘"). */
export function countRows(rows: readonly LibraryEligibilityRow[]): Counted {
  const eligible = rows.filter((row) => row.eligibility === 'ELIGIBLE')
  const open = rows.filter((row) => row.eligibility === 'OPEN')
  return { eligible, open }
}

/** Libraries joined by one side's regions (`matchedBy`). */
export function countSide(rows: readonly LibraryEligibilityRow[], basis: RegionBasis): number {
  return rows.filter((row) => row.eligibility === 'ELIGIBLE' && row.matchedBy.includes(basis)).length
}

/** The one 시도 of some regions ("서울"), or null for several or while the names are not known. */
function sidoOf(ids: readonly number[], labels: ReadonlyMap<number, string>): string | null {
  const sidos = new Set(ids.map((id) => labels.get(id)?.split(' ')[0]))
  return sidos.size === 1 ? ([...sidos][0] ?? null) : null
}

/**
 * The person's own numbers right after they pick a region (user 2026-10-20, ADR-0139 #4, handoff 5-A-4): "서울 강서구 →
 * 가입할 수 있는 도서관 70곳" and the pills [서울 거주로 32] [어디 살든 38] (the 방문 가입 pill taken out, user 2026-10-20), two names and
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
  // one pill a side: "서울 거주로 32", "인천 재직으로 12"; a side with no library is left out (user 2026-10-20)
  const sides = [
    { basis: 'RESIDENCE' as const, word: '거주', ids: residence },
    { basis: 'WORK' as const, word: '재직', ids: work },
  ]
    .filter((one) => one.ids.length > 0)
    .map((one) => ({ ...one, count: countSide(list.data, one.basis) }))
    .filter((one) => one.count > 0)
  const names = [...counted.eligible, ...counted.open].map((row) => row.name)
  return (
    <section className={styles.panel} aria-label={NUMBERS_TEXT.headline(place, total)}>
      <p className={styles.headline}>{NUMBERS_TEXT.headline(place, total)}</p>
      <p className={styles.pills}>
        {sides.map((one) => (
          <span key={one.basis} className={styles.pill} data-tone="mine">
            {NUMBERS_TEXT.byRegion(sidoOf(one.ids, labels), one.word, one.count)}
          </span>
        ))}
        <span className={styles.pill} data-tone="open">
          {NUMBERS_TEXT.open(counted.open.length)}
        </span>
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
