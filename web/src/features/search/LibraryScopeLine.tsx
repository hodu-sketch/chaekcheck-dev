import { Dialog } from 'radix-ui'
import { useEffect, useId, useRef, useState, type ReactNode } from 'react'
import { Link } from 'react-router'
import type { VisitFilter } from '../../api/types'
import { BottomSheet } from '../../components/BottomSheet'
import { useOptionalMyLibrary } from '../mylibrary/MyLibraryContext'
import { SaveHint } from '../mylibrary/SaveHint'
import { REGION_SAVE_FAILED, useRegions } from '../region/RegionContext'
import { RegionGrid, type RegionField } from '../region/RegionGrid'
import { MAX_REGIONS_PER_FIELD, type StoredRegions } from '../region/regionStorage'
import { useRegionPlace } from './regionPlace'
import type { PlanCount } from './searchSettings'
import { useLibraryList } from './useLibraryList'
import styles from './LibraryScopeLine.module.css'

/** Which grid the box opens on: 사는 곳, or the 일하는 곳 row ([일하는 곳도 더하기], flow S1). */
export type BoxFocus = RegionField

/** The texts of the "찾을 도서관" line, the box, the phone chip and the ⚙ switch (cycle 22 문구표) [확정]. */
export const SCOPE_TEXT = {
  pick: '사는 곳·일하는 곳 고르기',
  sheetTitle: '찾을 도서관',
  pickNote: '고르면 그 지역 기준으로 찾아요 · 안 고르면 어디 살든 가입되는 도서관에서 찾아요',
  addWork: '일하는 곳도 더하기',
  mine: '내 도서관',
  residence: '사는 곳',
  residenceShort: '거주',
  workShort: '재직',
  work: '일하는 곳',
  chosen: (count: number) => `직접 추가 ${count}곳`,
  chosenHint: '마이페이지에서 이름으로 더할 수 있어요',
  disabled: '꺼진 도서관',
  visitNone: '직접 가야 가입되는 도서관은 빼기',
  // under 직접 추가, apart from the 방문 가입 switch (user 2026-10-19: "가입조건 직접방문 거주 완전다른거인데 같이있어서
  // 헷갈려, 분리필요")
  open: (count: number | null) =>
    count === null ? '+ 어디 살든 가입되는 도서관도 늘 같이 찾아요' : `+ 어디 살든 가입되는 도서관 ${count}곳도 늘 같이 찾아요`,
  joinTitle: '가입 방법',
  visitNote: '한 번은 도서관에 가야 가입되는 곳을 결과에서 뺍니다',
  myPage: '마이페이지에서 전체 보기',
  accountFailed: '내 도서관을 불러오지 못했어요',
  retry: '다시 시도',
} as const

/** Names shown under "직접 추가 N곳" (R1-4 "이름 두어 개"); the rest as "외 N곳" [안]. */
const CHOSEN_NAMES_SHOWN = 2

/**
 * The folded line's words without "▾" (R3): "내 도서관: 서울 강남구 · 일하는 곳 성남시 · 방문 가입 뺌 · 직접 추가 2 · 68곳". Only
 * what differs from the default is added (R3-2); without 사는 곳 the 일하는 곳 leads ("내 도서관: 일하는 곳 성남시 …", user
 * approval). "N곳" only when the stored count was taken with the same inputs (R3-3, D5). Null for a first visitor.
 */
export function scopeLineText({
  residence,
  work,
  chosen,
}: {
  /** The place names of the regions, `undefined` while they are being read; null = none chosen. */
  residence: string | null | undefined
  work: string | null | undefined
  chosen: number
}): string | null {
  if (residence === null && work === null && chosen === 0) return null
  // the first residence and the first workplace, one each: "서울 강서구 거주 · 서울 강남구 재직" (user 2026-10-19:
  // "거주 재직 앞의 것 1개씩만 보이도록, 두번째 거주 두번째 재직 노출 될 필요 없어"; no "내 도서관:" in front);
  // 방문 가입 뺌, 직접 추가 and the count are in the box
  const parts = [
    residence !== null && `${residence ?? '…'} ${SCOPE_TEXT.residenceShort}`,
    work !== null && `${work ?? '…'} ${SCOPE_TEXT.workShort}`,
  ].filter((part): part is string => typeof part === 'string')
  if (parts.length > 0) return parts.join(' · ')
  return `직접 추가 ${chosen}곳`
}

/** The folded line's text for the current regions and added libraries (null for a first visitor). */
function useScopeLineText(): string | null {
  const { regions, libraryIds } = useRegions()
  // only the first of each field is named on the line
  const residencePlace = useRegionPlace(regions.residence.slice(0, 1))
  const workPlace = useRegionPlace(regions.work.slice(0, 1))
  return scopeLineText({
    residence: regions.residence.length === 0 ? null : (residencePlace ?? undefined),
    work: regions.work.length === 0 ? null : (workPlace ?? undefined),
    chosen: libraryIds.length,
  })
}

/** What the line and the chip show, from the region source's state. */
function useLineState(): 'waiting' | 'failed' | 'ready' {
  const { source, ready, accountFailed } = useRegions()
  if (accountFailed) return 'failed'
  // while the login check runs or the account is read, the line keeps its place and says nothing yet
  if (source === 'waiting' || (source === 'account' && !ready)) return 'waiting'
  return 'ready'
}

interface Common {
  /** 방문 가입 빼기 as stored for the next search (`prefs.visit`). */
  visit: VisitFilter
  onVisitChange: (visit: VisitFilter) => void
  /** The plan count kept after the last search (D5); shown only while its fingerprint matches what is saved now. */
  planCount: PlanCount | null
  /** How many 어디 살든 가입 libraries there are, when known (D5 `openCount`). */
  openCount: number | null
  /** A new `openCount` was learnt (the box read the library list for the added libraries' names). */
  onOpenCount: (count: number) => void
}

interface LineProps extends Common {
  /** The box is open, and on which grid; null = closed. */
  box: BoxFocus | null
  onBoxChange: (box: BoxFocus | null) => void
  /** The centred first screen: the line and the box are centred under the search box. */
  centered?: boolean
}

/**
 * The "찾을 도서관" line under the search box on wider screens (cycle 22 R1, flow S1): a first visitor's
 * [사는 곳·일하는 곳 고르기] with its small text, else the folded line "내 도서관: … ▾". Either opens the box (S1-box) that
 * floats under it without pushing the list, closed by a click outside or Esc. A failed account read says so with
 * [다시 시도] (D7). No 전국 marker beside it (user 2026-10-19: "내도서관 전국에서 찾는중도 빼는게"); a 전국 search shows
 * itself by the 「그 밖의 도서관」 group, and the next new search is 내 도서관 again (D10).
 */
export function LibraryScopeLine({ box, onBoxChange, centered = false, ...common }: LineProps) {
  const state = useLineState()
  const text = useScopeLineText()
  const first = text === null

  if (state === 'waiting') return <div className={styles.line} aria-hidden="true" data-waiting />
  if (state === 'failed') {
    return (
      <div className={styles.line} data-centered={centered || undefined}>
        <AccountFailed />
      </div>
    )
  }

  return (
    <div className={styles.root} data-centered={centered || undefined}>
      {/* a modal window in the middle of the screen (user 2026-10-19: "버튼 누르면 모달 창이 나와서 거기에서 고르도록") */}
      <Dialog.Root open={box !== null} onOpenChange={(open) => onBoxChange(open ? 'residence' : null)}>
        <div className={styles.line}>
          <Dialog.Trigger
            className={first ? styles.pick : styles.folded}
            aria-label={first ? undefined : `${SCOPE_TEXT.mine}: ${text}`}
          >
            {first ? SCOPE_TEXT.pick : text}
            {!first && <Caret />}
          </Dialog.Trigger>
        </div>
        <Dialog.Portal>
          <Dialog.Overlay className={styles.overlay} />
          <Dialog.Content
            className={styles.box}
            aria-describedby={undefined}
            onOpenAutoFocus={(event) => {
              // the 일하는 곳 row takes the focus itself (LibraryScopeBody)
              if (box === 'work') event.preventDefault()
            }}
          >
            <div className={styles.boxHead}>
              <Dialog.Title className={styles.boxTitle}>{SCOPE_TEXT.sheetTitle}</Dialog.Title>
              <Dialog.Close className={styles.boxClose} aria-label="닫기">
                <svg width="14" height="14" viewBox="0 0 14 14" aria-hidden="true">
                  <path d="M2 2l10 10M12 2L2 12" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" />
                </svg>
              </Dialog.Close>
            </div>
            {box !== null && (
              <LibraryScopeBody
                key={box}
                focus={box}
                visit={common.visit}
                onVisitChange={common.onVisitChange}
                openCount={common.openCount}
                onOpenCount={common.onOpenCount}
                onLeave={() => onBoxChange(null)}
              />
            )}
          </Dialog.Content>
        </Dialog.Portal>
      </Dialog.Root>
      {first && (
        // no [일하는 곳도 더하기] here (user 2026-10-19: "이건 pc/태블릿에 있으면 빼줘"); it is inside the box
        <p className={styles.pickNote}>{SCOPE_TEXT.pickNote}</p>
      )}
    </div>
  )
}

interface ChipProps extends Common {
  /** Opens the "찾을 도서관" sheet (`LibraryScopeSheet`). */
  onOpen: () => void
}

/**
 * Phones (R2-1): one chip under the search box, always there: "서울 강서구 거주 ▾", or "사는 곳·일하는 곳 고르기 ▾" for a
 * first visitor. A tap opens its own "찾을 도서관" sheet (user 2026-10-19; it opened the settings sheet before). The
 * account failure as on wider screens.
 */
export function LibraryScopeChip({ onOpen }: ChipProps) {
  const state = useLineState()
  const text = useScopeLineText()

  if (state === 'waiting') return <div className={styles.chipLine} aria-hidden="true" data-waiting />
  if (state === 'failed') {
    return (
      <div className={styles.chipLine}>
        <AccountFailed />
      </div>
    )
  }
  return (
    <div className={styles.chipLine}>
      <button
        type="button"
        className={styles.chip}
        data-pick={text === null || undefined}
        onClick={onOpen}
        aria-haspopup="dialog"
        aria-label={text === null ? undefined : `${SCOPE_TEXT.mine}: ${text}`}
      >
        {text ?? SCOPE_TEXT.pick}
        <Caret />
      </button>
    </div>
  )
}

interface SheetProps extends Pick<Common, 'visit' | 'onVisitChange' | 'openCount' | 'onOpenCount'> {
  open: boolean
  onOpenChange: (open: boolean) => void
  /** Which grid the sheet opens on. */
  focus: BoxFocus
}

/**
 * Phones (user 2026-10-19: "사는 곳 일하는 곳 고르기는 그것만 있고 설정에는 없었으면 해"): the chip's own bottom sheet,
 * "찾을 도서관" with the box's body only, the same shared sheet as ⚙ (handle, ⓧ, back, a tap outside).
 */
export function LibraryScopeSheet({ open, onOpenChange, focus, ...body }: SheetProps) {
  return (
    <BottomSheet open={open} onOpenChange={onOpenChange} title={SCOPE_TEXT.sheetTitle} closeButton closeOnBack>
      <div className={styles.sheet}>
        {/* the sheet's name is read from its hidden title; this line only shows it */}
        <h2 className={styles.sheetTitle} aria-hidden="true">
          {SCOPE_TEXT.sheetTitle}
        </h2>
        <LibraryScopeBody key={focus} focus={focus} {...body} onLeave={() => onOpenChange(false)} sheet />
      </div>
    </BottomSheet>
  )
}

function Caret() {
  return (
    <span className={styles.caret} aria-hidden="true">
      <svg width="10" height="7" viewBox="0 0 12 8">
        <path d="M1 1.5l5 5 5-5" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" />
      </svg>
    </span>
  )
}

/** D7: the account's 내 도서관 could not be read; the search went without it. */
function AccountFailed() {
  const myLibrary = useOptionalMyLibrary()
  return (
    <p className={styles.failed} role="alert">
      {SCOPE_TEXT.accountFailed}{' '}
      {myLibrary && (
        <button type="button" className={styles.linkButton} onClick={myLibrary.retry}>
          {SCOPE_TEXT.retry}
        </button>
      )}
    </p>
  )
}

/**
 * "직접 가야 가입되는 도서관은 빼기" (R8-1): the box, the phone "찾을 도서관" sheet and the ⚙ window on wider screens; only
 * kept for the next search.
 */
export function VisitSwitch({ visit, onChange }: { visit: VisitFilter; onChange: (visit: VisitFilter) => void }) {
  return (
    <label className={styles.visit}>
      <input
        type="checkbox"
        checked={visit === 'none'}
        onChange={(event) => onChange(event.target.checked ? 'none' : 'any')}
      />
      {SCOPE_TEXT.visitNone}
    </label>
  )
}

interface BodyProps {
  focus: BoxFocus
  visit: VisitFilter
  onVisitChange: (visit: VisitFilter) => void
  openCount: number | null
  onOpenCount: (count: number) => void
  /** [마이페이지에서 전체 보기] was pressed. */
  onLeave?: () => void
  /** Phones: inside the "찾을 도서관" sheet, the grids take the screen's width. */
  sheet?: boolean
}

/**
 * The box's body (R1-4), also the phone "찾을 도서관" sheet (R2-1): 사는 곳 (chips + grid), 일하는 곳
 * ([일하는 곳도 더하기] → chips + grid), 직접 추가 N곳 with names and the 어디 살든 가입 note, a thick band, "가입 방법"
 * with 방문 가입 빼기, and [마이페이지에서 전체 보기] (order by the user, 2026-10-19). A check only saves (R1-3): never a search. Before login the save hint shows under the part
 * that saved (R9); a failed account save says so (cycle 06 wording).
 */
export function LibraryScopeBody({
  focus,
  visit,
  onVisitChange,
  openCount,
  onOpenCount,
  onLeave,
  sheet = false,
}: BodyProps) {
  const { regions, setRegions, source, notices, saveFailed, libraryIds } = useRegions()
  const [workOpen, setWorkOpen] = useState(focus === 'work')
  const [saved, setSaved] = useState<RegionField | null>(null)
  const workRef = useRef<HTMLDivElement>(null)
  const residenceId = useId()
  const workId = useId()
  const joinId = useId()

  useEffect(() => {
    if (focus !== 'work') return
    const target = workRef.current?.querySelector<HTMLElement>('button, input:not([disabled])')
    target?.focus()
    workRef.current?.scrollIntoView?.({ block: 'nearest' })
  }, [focus])

  const toggle = (field: RegionField, id: number) => {
    const current = regions[field]
    const has = current.includes(id)
    if (!has && current.length >= MAX_REGIONS_PER_FIELD) return
    const next: StoredRegions = { ...regions, [field]: has ? current.filter((x) => x !== id) : [...current, id] }
    // a failure is on screen (saveFailed)
    setRegions(next).catch(() => undefined)
    setSaved(source === 'browser' ? field : null)
  }

  const grid = (field: RegionField) => (
    <div className={sheet ? styles.gridBleed : undefined}>
      <RegionGrid
        field={field}
        value={regions[field]}
        onToggle={(id) => toggle(field, id)}
        full={regions[field].length >= MAX_REGIONS_PER_FIELD}
        notices={field === 'residence' ? notices : undefined}
      />
    </div>
  )

  return (
    <div className={styles.body} data-sheet={sheet || undefined}>
      <div className={styles.part} role="group" aria-labelledby={residenceId}>
        <h3 id={residenceId} className={styles.partTitle}>
          {SCOPE_TEXT.residence}
        </h3>
        {grid('residence')}
        <SaveHint saved={saved === 'residence'} />
      </div>

      <div className={styles.part} role="group" aria-labelledby={workId} ref={workRef}>
        <h3 id={workId} className={styles.partTitle}>
          {SCOPE_TEXT.work}
        </h3>
        {workOpen || regions.work.length > 0 ? (
          grid('work')
        ) : (
          <button type="button" className={styles.addWork} onClick={() => setWorkOpen(true)}>
            {SCOPE_TEXT.addWork}
          </button>
        )}
        <SaveHint saved={saved === 'work'} />
      </div>

      {saveFailed && (
        <p className={styles.error} role="alert">
          {REGION_SAVE_FAILED}
        </p>
      )}

      <ChosenPart ids={libraryIds} openCount={openCount} onOpenCount={onOpenCount} />

      {/* which libraries (above) and how one joins (below) are two things: a thick band between them (user 2026-10-19) */}
      <div className={styles.band} aria-hidden="true" />

      <div className={styles.part} role="group" aria-labelledby={joinId}>
        <h3 id={joinId} className={styles.partTitle}>
          {SCOPE_TEXT.joinTitle}
        </h3>
        <VisitSwitch visit={visit} onChange={onVisitChange} />
        <p className={styles.note}>{SCOPE_TEXT.visitNote}</p>
      </div>

      <Link to="/my-libraries" className={styles.myPage} onClick={onLeave}>
        {SCOPE_TEXT.myPage}
      </Link>
    </div>
  )
}

/**
 * "직접 추가 N곳", a couple of names and how to add more (R1-4). The names come from `GET /api/v1/libraries` without
 * regions, asked only when there are added libraries and kept for 5 minutes (D4); the count of its OPEN rows is kept
 * as `openCount` (R3-4). A library not in that list is switched off: "꺼진 도서관".
 */
function ChosenPart({
  ids,
  openCount,
  onOpenCount,
}: {
  ids: number[]
  openCount: number | null
  onOpenCount: (count: number) => void
}) {
  const list = useLibraryList(ids.length > 0)
  const rows = list.data
  const openRows = rows?.filter((row) => row.eligibility === 'OPEN').length
  const keepOpenCount = useRef(onOpenCount)
  useEffect(() => {
    keepOpenCount.current = onOpenCount
  }, [onOpenCount])
  useEffect(() => {
    if (openRows !== undefined) keepOpenCount.current(openRows)
  }, [openRows])

  let names: ReactNode = null
  if (ids.length > 0 && rows) {
    const byId = new Map(rows.map((row) => [row.id, row.name]))
    const shown = ids.slice(0, CHOSEN_NAMES_SHOWN).map((id) => byId.get(id) ?? SCOPE_TEXT.disabled)
    const rest = ids.length - shown.length
    names = (
      <p className={styles.names}>
        {shown.join(', ')}
        {rest > 0 && ` 외 ${rest}곳`}
      </p>
    )
  }
  return (
    <div className={styles.part}>
      <h3 className={styles.partTitle}>{SCOPE_TEXT.chosen(ids.length)}</h3>
      {names}
      <p className={styles.note}>{SCOPE_TEXT.chosenHint}</p>
      <p className={styles.openNote}>{SCOPE_TEXT.open(openCount)}</p>
    </div>
  )
}
