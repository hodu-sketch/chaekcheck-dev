import { Dialog, Tabs } from 'radix-ui'
import { useState } from 'react'
import { ApiError } from '../../api/client'
import type { SidoNode } from '../../api/types'
import { resolveRegions } from './resolveRegions'
import { MAX_REGIONS_PER_FIELD, type StoredRegions } from './regionStorage'
import { useRegionLabels, useRegionTree } from './useRegionTree'
import styles from './RegionModal.module.css'

type Field = keyof StoredRegions

const FIELD_LABEL: Record<Field, string> = { residence: '거주', work: '직장' }

interface Props {
  open: boolean
  onOpenChange: (open: boolean) => void
  regions: StoredRegions
  /** Rejects when the save failed; the modal then stays open with the draft (cycle 06 step 9). */
  onSave: (regions: StoredRegions) => Promise<void>
  /** Where 저장 keeps them: this browser, or the signed-in account (cycle 06). */
  savedTo?: 'browser' | 'account'
  /** The field open first (S10 [변경] opens its own field, cycle 06 [안]). */
  initialField?: Field
}

/** S2: choose residence and work regions. Changes apply only on 저장 (flow.md). */
export function RegionModal({
  open,
  onOpenChange,
  regions,
  onSave,
  savedTo = 'browser',
  initialField = 'residence',
}: Props) {
  return (
    <Dialog.Root open={open} onOpenChange={onOpenChange}>
      <Dialog.Portal>
        <Dialog.Overlay className={styles.overlay} />
        <Dialog.Content className={styles.content}>
          {/* remount the draft every time the modal opens, so 취소 discards changes */}
          {open && (
            <RegionEditor
              initial={regions}
              initialField={initialField}
              savedTo={savedTo}
              onCancel={() => onOpenChange(false)}
              onSave={onSave}
            />
          )}
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  )
}

/** What a failed save says (cycle 06 step 9). Wording [안] except the general failure (flow S2). */
const SAVE_FAILED = '저장하지 못했어요. 잠시 뒤 다시 해 주세요'
const SIGNED_OUT = '로그인이 풀렸어요. 저장을 다시 누르면 이 브라우저에 저장돼요'

function RegionEditor({
  initial,
  initialField,
  savedTo,
  onCancel,
  onSave,
}: {
  initial: StoredRegions
  initialField: Field
  savedTo: 'browser' | 'account'
  onCancel: () => void
  onSave: (regions: StoredRegions) => Promise<void>
}) {
  const tree = useRegionTree()
  const labels = useRegionLabels(tree.data)
  const [draft, setDraft] = useState<StoredRegions>(initial)
  const [saving, setSaving] = useState(false)
  const [message, setMessage] = useState<string | null>(null)

  const save = async () => {
    setSaving(true)
    setMessage(null)
    try {
      await onSave(draft)
    } catch (error) {
      if (error instanceof ApiError && error.status === 401) {
        // the refresh failed too: the browser is signed out now, the next 저장 keeps them here
        setMessage(SIGNED_OUT)
      } else if (error instanceof ApiError && error.problem.code === 'invalid_region') {
        // changed regions since the tree was read: resolve them and let the user check again
        const outcome = await resolveRegions(draft).catch(() => null)
        if (outcome) setDraft(outcome.regions)
        setMessage(
          [...(outcome?.notices.map((notice) => notice.message) ?? []), '확인하고 다시 저장해 주세요'].join('. '),
        )
      } else {
        setMessage(SAVE_FAILED)
      }
    } finally {
      setSaving(false)
    }
  }
  const [field, setField] = useState<Field>(initialField)
  const [sidoId, setSidoId] = useState<number | null>(null)

  const chosen = draft[field]
  const full = chosen.length >= MAX_REGIONS_PER_FIELD
  const activeSido = tree.data?.find((s) => s.id === sidoId) ?? tree.data?.[0] ?? null

  const toggle = (id: number) =>
    setDraft((d) => ({
      ...d,
      [field]: d[field].includes(id) ? d[field].filter((x) => x !== id) : [...d[field], id],
    }))
  const remove = (target: Field, id: number) => setDraft((d) => ({ ...d, [target]: d[target].filter((x) => x !== id) }))

  return (
    <>
      <header className={styles.head}>
        <Dialog.Title className={styles.title}>내 지역</Dialog.Title>
        <Dialog.Description className={styles.description}>
          거주·직장 지역을 고르면 가입할 수 있는 도서관만 보여 드려요.{' '}
          {savedTo === 'account' ? '계정에 저장돼요.' : '이 브라우저에만 저장돼요.'}
        </Dialog.Description>
      </header>

      <Tabs.Root value={field} onValueChange={(value) => setField(value as Field)} className={styles.tabs}>
        <Tabs.List className={styles.tabList} aria-label="지역 종류">
          {(Object.keys(FIELD_LABEL) as Field[]).map((f) => (
            <Tabs.Trigger key={f} value={f} className={styles.tab}>
              {FIELD_LABEL[f]}
              <span className={styles.tabCount}>{draft[f].length}</span>
            </Tabs.Trigger>
          ))}
        </Tabs.List>

        {(Object.keys(FIELD_LABEL) as Field[]).map((f) => (
          <Tabs.Content key={f} value={f} className={styles.panel}>
            <ul className={styles.chips} aria-label={`고른 ${FIELD_LABEL[f]} 지역`}>
              {draft[f].length === 0 && <li className={styles.chipsEmpty}>아직 고른 지역이 없어요</li>}
              {draft[f].map((id) => (
                <li key={id} className={styles.chip}>
                  {labels.get(id) ?? `지역 ${id}`}
                  <button
                    type="button"
                    className={styles.chipRemove}
                    onClick={() => remove(f, id)}
                    aria-label={`${labels.get(id) ?? `지역 ${id}`} 빼기`}
                  >
                    ×
                  </button>
                </li>
              ))}
            </ul>
          </Tabs.Content>
        ))}
      </Tabs.Root>

      <div className={styles.picker}>
        {tree.isPending && <p className={styles.status}>지역 목록을 불러오는 중…</p>}
        {tree.isError && (
          <p className={styles.status} role="alert">
            지역 목록을 불러오지 못했습니다 ·{' '}
            <button type="button" className={styles.linkButton} onClick={() => tree.refetch()}>
              다시 시도
            </button>
          </p>
        )}
        {tree.data && activeSido && (
          <>
            <SidoList sidos={tree.data} activeId={activeSido.id} chosen={chosen} onSelect={setSidoId} />
            <fieldset className={styles.sigunguSet}>
              <legend className={styles.sigunguLegend}>
                {activeSido.name}
                {full && <span className={styles.limit}>{FIELD_LABEL[field]} 지역은 {MAX_REGIONS_PER_FIELD}곳까지 고를 수 있어요</span>}
              </legend>
              <div className={styles.sigunguGrid}>
                {activeSido.children.map((sigungu) => {
                  const checked = chosen.includes(sigungu.id)
                  return (
                    <label key={sigungu.id} className={styles.sigungu} data-checked={checked || undefined}>
                      <input
                        type="checkbox"
                        checked={checked}
                        disabled={!checked && full}
                        onChange={() => toggle(sigungu.id)}
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

      {message && (
        <p className={styles.saveMessage} role="alert">
          {message}
        </p>
      )}
      <footer className={styles.actions}>
        <button type="button" className={styles.clear} onClick={() => setDraft({ residence: [], work: [] })}>
          모두 지우기
        </button>
        <span className={styles.spacer} />
        <button type="button" className={styles.secondary} onClick={onCancel}>
          취소
        </button>
        <button type="button" className={styles.primary} onClick={save} disabled={saving}>
          {saving ? '저장 중' : '저장'}
        </button>
      </footer>
    </>
  )
}

function SidoList({
  sidos,
  activeId,
  chosen,
  onSelect,
}: {
  sidos: SidoNode[]
  activeId: number
  chosen: number[]
  onSelect: (id: number) => void
}) {
  return (
    <ul className={styles.sidoList} aria-label="시도">
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
