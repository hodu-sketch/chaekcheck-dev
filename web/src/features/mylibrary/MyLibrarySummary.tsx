import { Link } from 'react-router'
import { useRegions } from '../region/RegionContext'
import { useRegionLabels, useRegionTree } from '../region/useRegionTree'
import { useMyLibrary } from './MyLibraryContext'
import styles from './MyLibrarySummary.module.css'

/**
 * S1 "내 도서관" line after login (cycle 06 flow S1), in the place of the region line: what the "내 도서관" switch
 * searches. [관리] is the only way to S10 [확정]. On narrow screens each region list shows its first name and "외 N".
 */
export function MyLibrarySummary() {
  const myLibrary = useMyLibrary()
  const { regions } = useRegions()
  const tree = useRegionTree()
  const labels = useRegionLabels(tree.data)

  if (myLibrary.status === 'loading' || myLibrary.status === 'inactive') {
    return (
      <p className={styles.summary} role="status">
        불러오는 중
      </p>
    )
  }
  if (myLibrary.status === 'error') {
    return (
      <p className={styles.summary} role="alert">
        내 도서관을 불러오지 못했어요{' '}
        <button type="button" className={styles.action} onClick={myLibrary.retry}>
          다시 시도
        </button>
      </p>
    )
  }

  const manage = (
    <Link to="/my-libraries" className={styles.action}>
      관리
    </Link>
  )
  const chosen = myLibrary.libraryIds.length
  if (regions.residence.length === 0 && regions.work.length === 0 && chosen === 0) {
    return (
      <p className={styles.summary}>
        내 도서관이 비어 있어요. 지역을 고르거나 도서관을 추가하세요 {manage}
      </p>
    )
  }

  const names = (ids: number[]) => ids.map((id) => labels.get(id) ?? '…')
  const part = (kind: string, ids: number[]) => {
    if (ids.length === 0) return null
    const all = names(ids)
    return (
      <span className={styles.part}>
        <span className={styles.kind}>{kind}</span> <span className={styles.full}>{all.join(', ')}</span>
        <span className={styles.short}>
          {all[0]}
          {all.length > 1 && ` 외 ${all.length - 1}`}
        </span>
      </span>
    )
  }
  return (
    <p className={styles.summary}>
      <span className={styles.label}>내 도서관</span>
      {part('거주', regions.residence)}
      {part('직장', regions.work)}
      {chosen > 0 && <span className={styles.part}>직접 추가 {chosen}곳</span>}
      {manage}
    </p>
  )
}
