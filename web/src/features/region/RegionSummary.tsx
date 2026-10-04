import { ScopeRow, ScopeSummary } from '../search/ScopeSummary'
import panel from '../search/ScopeSummary.module.css'
import { hasRegions, type StoredRegions } from './regionStorage'
import { useRegionLabels, useRegionTree } from './useRegionTree'

/**
 * "내 지역" before login (flow.md S1), drawn like "내 도서관 관리" after login (user requests 2026-10-05): a button on
 * the switch line. Without regions it opens S2 at once; with regions it lists them and offers [변경].
 */
export function RegionSummary({ regions, onEdit }: { regions: StoredRegions; onEdit: () => void }) {
  const tree = useRegionTree()
  const labels = useRegionLabels(tree.data)

  if (!hasRegions(regions)) {
    return (
      <button type="button" className={panel.trigger} onClick={onEdit}>
        내 지역 설정
      </button>
    )
  }

  const describe = (ids: number[]) => ids.map((id) => labels.get(id) ?? '…').join(', ')
  return (
    <ScopeSummary label="내 지역" title="내 지역">
      <div className={panel.rows}>
        {regions.residence.length > 0 && <ScopeRow kind="거주">{describe(regions.residence)}</ScopeRow>}
        {regions.work.length > 0 && <ScopeRow kind="직장">{describe(regions.work)}</ScopeRow>}
      </div>
      <button type="button" className={panel.action} onClick={onEdit}>
        변경
      </button>
    </ScopeSummary>
  )
}
