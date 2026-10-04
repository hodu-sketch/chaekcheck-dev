import { hasRegions, type StoredRegions } from './regionStorage'
import { useRegionLabels, useRegionTree } from './useRegionTree'
import styles from './RegionSummary.module.css'

/**
 * "내 지역: 거주 서울 마포구 · 직장 서울 중구 · 변경" (flow.md S1), before login. Drawn like the "내 도서관" box after
 * login (user request 2026-10-05): one grey-violet box, each region kind as a white chip, the button at the right end.
 */
export function RegionSummary({ regions, onEdit }: { regions: StoredRegions; onEdit: () => void }) {
  const tree = useRegionTree()
  const labels = useRegionLabels(tree.data)

  if (!hasRegions(regions)) {
    return (
      <p className={styles.summary}>
        <span className={styles.hint}>내 지역을 고르면 빌릴 수 있는 도서관만 보여 드려요</span>
        <button type="button" className={styles.edit} onClick={onEdit}>
          설정
        </button>
      </p>
    )
  }

  const describe = (ids: number[]) => ids.map((id) => labels.get(id) ?? '…').join(', ')
  return (
    <p className={styles.summary}>
      <span className={styles.label}>내 지역</span>
      {regions.residence.length > 0 && (
        <span className={styles.part}>
          <span className={styles.kind}>거주</span> {describe(regions.residence)}
        </span>
      )}
      {regions.work.length > 0 && (
        <span className={styles.part}>
          <span className={styles.kind}>직장</span> {describe(regions.work)}
        </span>
      )}
      <button type="button" className={styles.edit} onClick={onEdit}>
        변경
      </button>
    </p>
  )
}
