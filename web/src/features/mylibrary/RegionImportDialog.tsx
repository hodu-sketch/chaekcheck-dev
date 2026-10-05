import { useQuery } from '@tanstack/react-query'
import { Dialog } from 'radix-ui'
import { useEffect, useMemo, useState } from 'react'
import { ApiError, fetchLibraries } from '../../api/client'
import { importMyLibraries } from '../../api/myLibraries'
import { direction } from '../../lib/josa'
import { useOptionalNotice } from '../auth/NoticeContext'
import { loadRegions, type StoredRegions } from '../region/regionStorage'
import { resolveAll } from '../region/resolveRegions'
import { useRegionLabels, useRegionTree } from '../region/useRegionTree'
import { loadChosenLibraries } from './chosenLibraryStorage'
import { useMyLibrary, useOptionalMyLibrary } from './MyLibraryContext'
import {
  answeredAccounts,
  libraryImportNote,
  partsToOffer,
  planImport,
  rememberAnswer,
  type ImportParts,
  type ImportPlan,
} from './regionImport'
import styles from './RegionImportDialog.module.css'

/**
 * S11: "이 브라우저에 저장된 것을 계정에 저장할까요?" right after login (cycle 07 flow S11, ADR-0049, ADR-0057): this
 * browser's regions when the account has none, and the libraries this browser added when the account has none. Reading
 * this browser's values here is the one exception to "signed in = server values only" (C18); they are left in place
 * either way.
 */
export function RegionImportDialog() {
  // the search screen's tests render the layout without the provider
  return useOptionalMyLibrary() ? <RegionImport /> : null
}

interface BrowserValues {
  regions: StoredRegions
  libraryIds: number[]
}

function RegionImport() {
  const myLibrary = useMyLibrary()
  const [dismissed, setDismissed] = useState(false)
  // this browser's values, read once per account
  const browser = useMemo<BrowserValues | null>(
    () => (myLibrary.accountId === null ? null : { regions: loadRegions(), libraryIds: loadChosenLibraries() }),
    [myLibrary.accountId],
  )

  const ready = myLibrary.mode === 'account' && myLibrary.status === 'ready' && myLibrary.regions !== null
  const parts =
    dismissed || browser === null || !ready
      ? null
      : partsToOffer({
          accountId: myLibrary.accountId,
          serverRegions: { residence: myLibrary.regions!.residenceRegionIds, work: myLibrary.regions!.workRegionIds },
          serverLibraries: myLibrary.libraryIds,
          browserRegions: browser.regions,
          browserLibraries: browser.libraryIds,
          answered: answeredAccounts(),
        })
  const open = parts !== null

  return (
    <Dialog.Root open={open} onOpenChange={(next) => !next && setDismissed(true)}>
      <Dialog.Portal>
        <Dialog.Overlay className={styles.overlay} />
        <Dialog.Content className={styles.content}>
          {open && browser && myLibrary.accountId !== null && (
            <ImportBody
              accountId={myLibrary.accountId}
              parts={parts}
              browser={browser}
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
  parts,
  browser,
  onDone,
}: {
  accountId: number
  parts: ImportParts
  browser: BrowserValues
  onDone: () => void
}) {
  const { saveRegions, setLibraryIds, maxLibraries, retry } = useMyLibrary()
  const notice = useOptionalNotice()
  const tree = useRegionTree()
  const labels = useRegionLabels(tree.data)
  // the names of the browser's libraries (the public list, no regions needed for the names)
  const list = useQuery({
    queryKey: ['libraries', '', ''],
    queryFn: ({ signal }) => fetchLibraries([], [], signal),
    enabled: parts.libraries,
    staleTime: 5 * 60_000,
  })
  const [plan, setPlan] = useState<ImportPlan | null>(parts.regions ? null : { regions: browser.regions, replacedTo: [], dropped: [] })
  const [saving, setSaving] = useState(false)
  const [failed, setFailed] = useState(false)

  // resolve the browser's ids when the dialog opens; without an answer the ids are offered as they are
  useEffect(() => {
    if (!parts.regions) return
    let cancelled = false
    const ids = [...new Set([...browser.regions.residence, ...browser.regions.work])]
    resolveAll(ids)
      .then((resolutions) => !cancelled && setPlan(planImport(browser.regions, resolutions)))
      .catch(() => !cancelled && setPlan({ regions: browser.regions, replacedTo: [], dropped: [] }))
    return () => {
      cancelled = true
    }
  }, [browser.regions, parts.regions])

  const describe = (ids: number[]) => ids.map((id) => labels.get(id) ?? '…').join(', ')
  const names = useMemo(() => {
    const byId = new Map((list.data ?? []).map((row) => [row.id, row.name]))
    return browser.libraryIds.map((id) => byId.get(id) ?? '…').join(', ')
  }, [list.data, browser.libraryIds])

  const save = async () => {
    if (!plan) return
    setSaving(true)
    setFailed(false)
    const notes: string[] = []
    try {
      if (parts.regions) {
        await saveRegions({ residenceRegionIds: plan.regions.residence, workRegionIds: plan.regions.work })
        if (plan.dropped.length > 0) {
          const dropped = plan.dropped.filter((name): name is string => name !== null)
          const which = dropped.length > 0 ? `(${dropped.join(', ')})` : ''
          notes.push(`나뉘었거나 찾을 수 없어 저장하지 않은 지역이 있어요${which}. 지역을 다시 골라 주세요`)
        }
      }
      if (parts.libraries) {
        try {
          const saved = await importMyLibraries(browser.libraryIds)
          setLibraryIds(() => saved.libraryIds)
          const note = libraryImportNote(browser.libraryIds.length, saved.libraryIds.length, maxLibraries)
          if (note) notes.push(note)
        } catch (error) {
          // libraries came to the account meanwhile (another tab): read the account again and use its values [안]
          if (error instanceof ApiError && error.problem.code === 'library_import_not_empty') retry()
          else throw error
        }
      }
      rememberAnswer(accountId)
      if (notes.length > 0) notice?.showNotice(notes.join(' '))
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
      <Dialog.Title className={styles.title}>이 브라우저에 저장된 것을 계정에 저장할까요?</Dialog.Title>
      <Dialog.Description asChild>
        <div className={styles.body}>
          {parts.regions &&
            (plan === null ? (
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
            ))}
          {parts.libraries && (
            <p className={styles.libraries}>
              직접 넣은 도서관 {browser.libraryIds.length}곳: {names}
            </p>
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
