import { Dialog } from 'radix-ui'
import { useEffect, useMemo, useState } from 'react'
import { direction } from '../../lib/josa'
import { useOptionalNotice } from '../auth/NoticeContext'
import { loadRegions, type StoredRegions } from '../region/regionStorage'
import { resolveAll } from '../region/resolveRegions'
import { useRegionLabels, useRegionTree } from '../region/useRegionTree'
import { useMyLibrary, useOptionalMyLibrary } from './MyLibraryContext'
import { answeredAccounts, planImport, rememberAnswer, shouldOfferImport, type ImportPlan } from './regionImport'
import styles from './RegionImportDialog.module.css'

/**
 * S11: "이 브라우저에 고른 지역이 있어요. 계정에 저장할까요?" right after login, when the account has no saved regions
 * and this browser has some (flow S11, ADR-0049). Reading this browser's regions here is the one exception to "signed
 * in = server values only" (C18); they are left in place either way.
 */
export function RegionImportDialog() {
  // the search screen's tests render the layout without the provider
  return useOptionalMyLibrary() ? <RegionImport /> : null
}

function RegionImport() {
  const myLibrary = useMyLibrary()
  const [dismissed, setDismissed] = useState(false)
  // this browser's regions, read once per account
  const browserRegions = useMemo(() => (myLibrary.accountId === null ? null : loadRegions()), [myLibrary.accountId])

  const serverRegions =
    myLibrary.status === 'ready' && myLibrary.regions
      ? { residence: myLibrary.regions.residenceRegionIds, work: myLibrary.regions.workRegionIds }
      : null
  const open =
    !dismissed &&
    browserRegions !== null &&
    shouldOfferImport({
      accountId: myLibrary.accountId,
      serverRegions,
      browserRegions,
      answered: answeredAccounts(),
    })

  return (
    <Dialog.Root open={open} onOpenChange={(next) => !next && setDismissed(true)}>
      <Dialog.Portal>
        <Dialog.Overlay className={styles.overlay} />
        <Dialog.Content className={styles.content}>
          {open && browserRegions && myLibrary.accountId !== null && (
            <ImportBody
              accountId={myLibrary.accountId}
              browserRegions={browserRegions}
              onDone={() => setDismissed(true)}
            />
          )}
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  )
}

function ImportBody({
  accountId,
  browserRegions,
  onDone,
}: {
  accountId: number
  browserRegions: StoredRegions
  onDone: () => void
}) {
  const { saveRegions } = useMyLibrary()
  const notice = useOptionalNotice()
  const tree = useRegionTree()
  const labels = useRegionLabels(tree.data)
  const [plan, setPlan] = useState<ImportPlan | null>(null)
  const [saving, setSaving] = useState(false)
  const [failed, setFailed] = useState(false)

  // resolve the browser's ids when the dialog opens; without an answer the ids are offered as they are
  useEffect(() => {
    let cancelled = false
    const ids = [...new Set([...browserRegions.residence, ...browserRegions.work])]
    resolveAll(ids)
      .then((resolutions) => !cancelled && setPlan(planImport(browserRegions, resolutions)))
      .catch(() => !cancelled && setPlan({ regions: browserRegions, replacedTo: [], dropped: [] }))
    return () => {
      cancelled = true
    }
  }, [browserRegions])

  const describe = (ids: number[]) => ids.map((id) => labels.get(id) ?? '…').join(', ')

  const save = async () => {
    if (!plan) return
    setSaving(true)
    setFailed(false)
    try {
      await saveRegions({ residenceRegionIds: plan.regions.residence, workRegionIds: plan.regions.work })
      rememberAnswer(accountId)
      if (plan.dropped.length > 0) {
        const names = plan.dropped.filter((name): name is string => name !== null)
        const which = names.length > 0 ? `(${names.join(', ')})` : ''
        notice?.showNotice(`나뉘었거나 찾을 수 없어 저장하지 않은 지역이 있어요${which}. 지역을 다시 골라 주세요`)
      }
      onDone()
    } catch {
      setFailed(true)
    } finally {
      setSaving(false)
    }
  }

  const decline = () => {
    rememberAnswer(accountId)
    onDone()
  }

  return (
    <>
      <Dialog.Title className={styles.title}>이 브라우저에 고른 지역이 있어요. 계정에 저장할까요?</Dialog.Title>
      <Dialog.Description asChild>
        <div className={styles.body}>
          {plan === null ? (
            <p className={styles.status}>지역을 확인하는 중</p>
          ) : (
            <dl className={styles.regions}>
              {plan.regions.residence.length > 0 && (
                <div>
                  <dt>거주</dt>
                  <dd>{describe(plan.regions.residence)}</dd>
                </div>
              )}
              {plan.regions.work.length > 0 && (
                <div>
                  <dt>직장</dt>
                  <dd>{describe(plan.regions.work)}</dd>
                </div>
              )}
            </dl>
          )}
          {plan?.replacedTo.map((name) => (
            <p key={name} className={styles.note}>
              행정구역이 바뀌어 {direction(name)} 저장돼요
            </p>
          ))}
          {plan && plan.dropped.length > 0 && (
            <p className={styles.note}>나뉘었거나 찾을 수 없는 지역은 빼고 저장해요. 저장한 뒤 다시 골라 주세요</p>
          )}
          {failed && (
            <p className={styles.error} role="alert">
              저장하지 못했어요. 잠시 뒤 다시 해 주세요
            </p>
          )}
        </div>
      </Dialog.Description>
      <div className={styles.actions}>
        <button type="button" className={styles.secondary} onClick={decline} disabled={saving}>
          저장하지 않기
        </button>
        <button type="button" className={styles.primary} onClick={save} disabled={plan === null || saving}>
          {saving ? '저장 중' : '계정에 저장'}
        </button>
      </div>
    </>
  )
}
