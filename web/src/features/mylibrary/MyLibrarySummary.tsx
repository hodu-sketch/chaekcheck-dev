import { Link } from 'react-router'
import { useRegions } from '../region/RegionContext'
import { useRegionLabels, useRegionTree } from '../region/useRegionTree'
import { ScopeRow, ScopeSummary } from '../search/ScopeSummary'
import panel from '../search/ScopeSummary.module.css'
import { useMyLibrary } from './MyLibraryContext'

/**
 * S1 "내 도서관" (cycle 06 flow S1): what the "내 도서관" switch searches. It sits behind a "내 도서관 관리" button on the
 * switch line and opens as a short list (user request 2026-10-05; a line under the search field before). [관리] is the
 * only way to S10 [확정]. Since cycle 07 the same before login (this browser's regions and libraries); with neither,
 * the button says "내 도서관 설정" and opens S10 at once [확정] (cycle 07 flow S1).
 */
export function MyLibrarySummary() {
  const myLibrary = useMyLibrary()
  const { regions } = useRegions()
  const tree = useRegionTree()
  const labels = useRegionLabels(tree.data)

  if (
    myLibrary.mode === 'browser' &&
    regions.residence.length === 0 &&
    regions.work.length === 0 &&
    myLibrary.libraryIds.length === 0
  ) {
    return (
      <Link to="/my-libraries" className={panel.trigger}>
        내 도서관 설정
      </Link>
    )
  }

  const manage = (
    <Link to="/my-libraries" className={panel.action}>
      관리
    </Link>
  )

  let body
  if (myLibrary.status === 'loading' || myLibrary.status === 'inactive') {
    body = (
      <p className={panel.note} role="status">
        불러오는 중
      </p>
    )
  } else if (myLibrary.status === 'error') {
    body = (
      <>
        <p className={panel.note} role="alert">
          내 도서관을 불러오지 못했어요
        </p>
        <button type="button" className={panel.action} onClick={myLibrary.retry}>
          다시 시도
        </button>
      </>
    )
  } else {
    const chosen = myLibrary.libraryIds.length
    const names = (ids: number[]) => ids.map((id) => labels.get(id) ?? '…').join(', ')
    const empty = regions.residence.length === 0 && regions.work.length === 0 && chosen === 0
    body = (
      <>
        {empty ? (
          <p className={panel.note}>내 도서관이 비어 있어요. 지역을 고르거나 도서관을 추가하세요</p>
        ) : (
          <div className={panel.rows}>
            {regions.residence.length > 0 && <ScopeRow kind="거주">{names(regions.residence)}</ScopeRow>}
            {regions.work.length > 0 && <ScopeRow kind="직장">{names(regions.work)}</ScopeRow>}
            {chosen > 0 && <ScopeRow kind="직접 추가">{chosen}곳</ScopeRow>}
          </div>
        )}
        {manage}
      </>
    )
  }

  return (
    <ScopeSummary label="내 도서관 관리" title="내 도서관">
      {body}
    </ScopeSummary>
  )
}
