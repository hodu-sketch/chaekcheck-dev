import { useEffect, useId, useRef, useState, type ReactNode } from 'react'
import { Link } from 'react-router'
import type { VisitFilter } from '../../api/types'
import { BottomSheet } from '../../components/BottomSheet'
import { useOptionalMyLibrary } from '../mylibrary/MyLibraryContext'
import { SaveHint } from '../mylibrary/SaveHint'
import { REGION_SAVE_FAILED, useRegions } from '../region/RegionContext'
import { RegionGrid, type RegionField } from '../region/RegionGrid'
import { useRegionLabels, useRegionTree } from '../region/useRegionTree'
import type { StoredRegions } from '../region/regionStorage'
import { ro } from './regionPlace'
import { useRegionPlace } from './regionPlace'
import type { PlanCount } from './searchSettings'
import styles from './LibraryScopeLine.module.css'

/** Which grid the box opens on: 사는 곳, or the 일하는 곳 row ([일하는 곳도 더하기], flow S1). */
export type BoxFocus = RegionField

/** What was saved since a search made without regions or added libraries: a place with its field, or added libraries. */
export type PickedSince = { field: 'residence' | 'work'; place: string } | { field: 'libraries' } | null

/**
 * The search on screen, for the condition card of a results screen (user 2026-10-19); only for a search made without
 * regions or added libraries, not 전국 and not after a failed account read (the results' line R7 before).
 */
export interface ScopeSearched {
  /** How many libraries the search planned (R7-1); null when not known, and the card goes without the number. */
  planCount: number | null
  /** Regions (or libraries) were saved since (R7-2): the grey line asks for [검색]. */
  regionsPicked: boolean
  picked: PickedSince
}

/** The texts of the "찾을 도서관" line, the box, the phone chip and the ⚙ switch (cycle 22 문구표) [확정]. */
export const SCOPE_TEXT = {
  // no "조건·설정" words on the line (user 2026-10-20, ADR-0139 #3, handoff 5-A-3)
  // user 2026-10-20: "지역을 선택하시면 빌릴 수 있는 도서관에서 책을 더 찾아드려요 / 지역 선택"
  // both states (user 2026-10-20: "지역설정을 빼고 지역을 선택해주세요 이걸로")
  pick: '지역을 선택해주세요',
  // user 2026-10-20: "지역을 선택하시면 빌릴 수 있는 전자도서관이 늘어나요"
  conditionNone: '지역을 선택하시면 빌릴 수 있는 전자도서관이 늘어나요',
  condition: (text: string) => text,
  // user 2026-10-20: "바꾸기 말고 지역 설정"
  edit: '지역 설정',
  conditionHint: (count: number | null) =>
    `안 골라도 어디 살든 가입되는 도서관${count === null ? '' : ` ${count}곳`}에서 찾아요 · 일하는 곳도 더할 수 있어요`,
  // a results screen of a search made without regions or added libraries (the results' own line before, user
  // 2026-10-19: "이거 중복이라서 … 내용을 없애고 위에 좀 써줘"); {N} = the planned libraries, when known
  conditionSearched: (count: number | null) =>
    `어디 살든 가입되는 도서관${count === null ? '' : ` ${count}곳`}에서 찾았어요`,
  searchedHint: '사는 곳을 알려 주시면 그 지역 도서관도 보여요',
  // after a region was saved on such a results screen (user 2026-10-19: "~했으니 ~하고싶으면 검색 버튼 눌러주세요"),
  // followed by "[검색]을 눌러 주세요"
  pickedBefore: (picked: PickedSince) => {
    if (!picked) return '지역을 바꿨어요. 바뀐 지역으로 찾으려면 '
    if (picked.field === 'libraries') return '직접 추가한 도서관이 생겼어요. 그 도서관부터 보려면 '
    const field = picked.field === 'residence' ? '사는 곳' : '일하는 곳'
    return `${field}을 ${picked.place}${ro(picked.place)} 골랐어요. 그 지역 도서관부터 보려면 `
  },
  // the 「온라인에서 가입 가능한 곳만 보기」 switch differs from the search on screen (user 2026-10-19: "이거 바꾸면 검색
  // 조건이 달라지는 건데 검색 다시 하라는 문구 없어 추가해줘"), followed by "[검색]을 눌러 주세요"
  visitChangedBefore: '가입 방법을 바꿨어요. 바뀐 조건으로 찾으려면 ',
  pressSearch: '[검색]',
  pressSearchAfter: '을 눌러 주세요',
  sheetTitle: '찾을 도서관',
  addWork: '일하는 곳도 더하기',
  mine: '내 도서관',
  // user 2026-10-20: "사는 곳 말고 거주"
  residence: '거주',
  residenceShort: '거주',
  workShort: '재직',
  work: '일하는 곳',
  chosen: (count: number) => `직접 추가 ${count}곳`,
  chosenHint: '마이페이지에서 이름으로 더할 수 있어요',
  disabled: '꺼진 도서관',
  // a switch, on = visit=none (user 2026-10-19; "직접 가야 가입되는 도서관은 빼기" before)
  visitNone: '온라인에서 가입 가능한 곳만 보기',
  // under 직접 추가, apart from the 방문 가입 switch (user 2026-10-19: "가입조건 직접방문 거주 완전다른거인데 같이있어서
  // 헷갈려, 분리필요")
  open: (count: number | null) =>
    count === null ? '+ 어디 살든 가입되는 도서관도 늘 같이 찾아요' : `+ 어디 살든 가입되는 도서관 ${count}곳도 늘 같이 찾아요`,
  joinTitle: '가입 방법',
  visitNote: '도서관에 직접 가야 가입되는 곳은 결과에서 빼요',
  myPage: '마이페이지에서 전체 보기',
  // under the main page's one region (user 2026-10-20: "마이페이지에서 뭐 이것저것 더 설정할 수 있다 아래 깔아두고 또 밑줄
  // 진한글자 해서 마이페이지로")
  // in one sentence (user 2026-10-20: "이거 그냥 뭉뚱그려서 설명해줘")
  moreBefore: '더 자세한 설정은 ',
  myPageWord: '마이페이지',
  moreAfter: '에서 할 수 있어요',
  // the results, the book page and the picks have no region choice (user 2026-10-20: "검색창, 추천도서 창에서 지역
  // 선택하는 거 싹 다 빼고 … 마이페이지 이동 링크")
  noteNoneBefore: '누구나 가입할 수 있는 도서관에서만 찾고 있어요. 더 자세히 찾으려면 ',
  noteNoneAfter: '에서 지역을 골라 주세요',
  // the main page's box, instead of the 직접 추가 part (user 2026-10-20: "직접추가 도서관은 마이페이지 가라")
  chosenMyPage: '직접 추가는 마이페이지에서 →',
  // a screen that is not a search (the book page) after the regions or the switch changed (user 2026-10-20: "여기 검색에도
  // 다른 것들처럼 정보 바꼈으니까 검색 새로하라고하고 가능한 버튼 만들어줘"; no 반영하기 in the window any more)
  changedBefore: '조건을 바꿨어요. 바뀐 조건으로 찾으려면 ',
  searchAgain: '다시 찾기',
  accountFailed: '내 도서관을 불러오지 못했어요',
  retry: '다시 시도',
} as const

/**
 * The folded line's words without "▾" (R3): "내 도서관: 서울 강남구 · 일하는 곳 성남시 · 방문 가입 뺌 · 직접 추가 2 · 68곳". Only
 * what differs from the default is added (R3-2); without 사는 곳 the 일하는 곳 leads ("내 도서관: 일하는 곳 성남시 …", user
 * approval). "N곳" only when the stored count was taken with the same inputs (R3-3, D5). Null for a first visitor.
 */
export function scopeLineText({
  residence,
  work,
  chosen,
  moreResidence = 0,
  moreWork = 0,
}: {
  /** The place names of the regions, `undefined` while they are being read; null = none chosen. */
  residence: string | null | undefined
  work: string | null | undefined
  chosen: number
  /** How many more residences / workplaces than the named first one: "외 N곳". */
  moreResidence?: number
  moreWork?: number
}): string | null {
  if (residence === null && work === null && chosen === 0) return null
  // the first residence and the first workplace, one each: "서울 강서구 거주 · 서울 강남구 재직" (user 2026-10-19:
  // "거주 재직 앞의 것 1개씩만 보이도록, 두번째 거주 두번째 재직 노출 될 필요 없어"; no "내 도서관:" in front);
  // 방문 가입 뺌, 직접 추가 and the count are in the box
  // "외 N곳" after the first when there are more (user 2026-10-20: "거주나 직장 지역 조건 많이 할 때 외 1곳 이런거
  // 추가해줘 없으니까 조건이 적용 안 된 것처럼 보인다")
  const more = (count: number) => (count > 0 ? ` 외 ${count}곳` : '')
  // "{서울 강남구}에 살아요 · {성남시}에서 일해요" (user 2026-10-20, ADR-0139 #3: no 거주/재직 tail)
  const parts = [
    residence !== null && `${residence ?? '…'}${more(moreResidence)}에 살아요`,
    work !== null && `${work ?? '…'}${more(moreWork)}에서 일해요`,
  ].filter((part): part is string => typeof part === 'string')
  if (parts.length > 0) return parts.join(' · ')
  return `직접 추가한 도서관 ${chosen}곳에서 찾아요`
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
    moreResidence: Math.max(0, regions.residence.length - 1),
    moreWork: Math.max(0, regions.work.length - 1),
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
  /** A results screen of a search made without regions or added libraries; null or absent elsewhere. */
  searched?: ScopeSearched | null
  /** A results screen whose search was made with the other 「온라인에서 가입 가능한 곳만 보기」 value. */
  visitChanged?: boolean
  /** A screen that is not a search: its own line under the card instead (`SearchAgainHint`), or none. */
  changedHint?: ReactNode
  /** A results screen whose regions or added libraries changed since its search (searched with some, too). */
  regionsChanged?: boolean
  /** Searches again with what is saved now: [검색] in the grey line becomes a button (user 2026-10-20). */
  onSearchAgain?: () => void
}

interface LineProps extends Common {
  /** The box is open, and on which grid; null = closed. */
  box: BoxFocus | null
  onBoxChange: (box: BoxFocus | null) => void
}


/** "조건을 바꿨어요. 바뀐 조건으로 찾으려면 [다시 찾기]": the book page's own search again, a button in the line. */
export function SearchAgainHint({ onClick }: { onClick: () => void }) {
  return (
    <span role="status">
      {SCOPE_TEXT.changedBefore}
      <button type="button" className={styles.againButton} onClick={onClick}>
        {SCOPE_TEXT.searchAgain}
      </button>
    </span>
  )
}

/** "… [검색]을 눌러 주세요": the search on screen no longer matches what is saved. */
function PressSearch({ before, onSearch }: { before: string; onSearch?: () => void }) {
  return (
    <span role="status">
      {before}
      {onSearch ? (
        // the [검색] in the line searches too, so a phone need not scroll up to the button (user 2026-10-20)
        <button type="button" className={styles.againButton} onClick={onSearch}>
          {SCOPE_TEXT.pressSearch}
        </button>
      ) : (
        <b>{SCOPE_TEXT.pressSearch}</b>
      )}
      {SCOPE_TEXT.pressSearchAfter}
    </span>
  )
}


/**
 * The "찾을 도서관" condition line under the search box on wider screens (cycle 22 R1, flow S1; a card since the first
 * screen redesign, user 2026-10-19). Its link opens the box (S1-box), a modal in the middle of the screen, closed by a
 * click outside or Esc. A failed account read says so with [다시 시도] (D7). No 전국 marker beside it (user
 * 2026-10-19: "내도서관 전국에서 찾는중도 빼는게"); a 전국 search shows itself by the 「그 밖의 도서관」 group, and the next
 * new search is 내 도서관 again (D10).
 */
export function LibraryScopeLine({ box, onBoxChange }: LineProps) {
  const state = useLineState()
  const panelId = useId()
  // inline: Esc closes the box wherever the focus is (it is part of the page, not a window that holds the focus)
  const inlineOpen = box !== null
  useEffect(() => {
    if (!inlineOpen) return
    const escape = (event: KeyboardEvent) => {
      if (event.key === 'Escape') onBoxChange(null)
    }
    document.addEventListener('keydown', escape)
    return () => document.removeEventListener('keydown', escape)
  }, [inlineOpen, onBoxChange])
  // inline: when it opens, the page scrolls only as far as the whole box needs to be in view, the search box above it
  // staying on screen where it fits (user 2026-10-20: "한 화면에서 다 보이도록")
  const rootRef = useRef<HTMLDivElement>(null)
  useEffect(() => {
    if (!inlineOpen) return
    const frame = requestAnimationFrame(() => {
      const bottom = rootRef.current?.getBoundingClientRect().bottom
      if (bottom === undefined) return
      const by = bottom + 16 - window.innerHeight
      if (by > 0) window.scrollBy?.({ top: by })
    })
    return () => cancelAnimationFrame(frame)
  }, [inlineOpen])

  if (state === 'waiting') return <div className={styles.line} aria-hidden="true" data-waiting />
  if (state === 'failed') {
    return (
      <div className={styles.line}>
        <AccountFailed />
      </div>
    )
  }

  const close = (
    <svg width="14" height="14" viewBox="0 0 14 14" aria-hidden="true">
      <path d="M2 2l10 10M12 2L2 12" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" />
    </svg>
  )

  // the main page on PC and tablets: the box opens under the card and pushes the page down, no window (user
  // 2026-10-20: "메인에서만큼은 이거 누르면 모달창 말고 아래로 내려서 보이게 해줘"); the other screens have no box since
  // the same day (ScopeNote)
  return (
    <div ref={rootRef} className={styles.root}>
      {/* one smaller violet button, no card with words (user 2026-10-20: "버튼을 통으로 바꾸라고 … 지역을 선택해주세요") */}
      <div className={styles.pickRow}>
        <button
          type="button"
          className={styles.pickButton}
          aria-expanded={box !== null}
          aria-controls={box !== null ? panelId : undefined}
          onClick={() => onBoxChange(box === null ? 'residence' : null)}
        >
          {SCOPE_TEXT.pick}
        </button>
        <PickedTag />
      </div>
      {box !== null && (
        <section id={panelId} className={styles.panel} aria-label={SCOPE_TEXT.sheetTitle}>
          <button
            type="button"
            className={`${styles.boxClose} ${styles.panelClose}`}
            aria-label="닫기"
            onClick={() => onBoxChange(null)}
          >
            {close}
          </button>
          <LibraryScopeBody onLeave={() => onBoxChange(null)} compact />
        </section>
      )}
    </div>
  )
}

interface ChipProps extends Common {
  /** Opens the "찾을 도서관" sheet (`LibraryScopeSheet`). */
  onOpen: () => void
}

/**
 * Phones (R2-1): the same condition card under the search box, always there; its link opens its own "찾을 도서관"
 * sheet (user 2026-10-19; it opened the settings sheet before). The account failure as on wider screens.
 */
export function LibraryScopeChip({
  onOpen,
}: ChipProps) {
  const state = useLineState()

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
      <div className={styles.pickRow}>
        <button type="button" className={styles.pickButton} onClick={onOpen} aria-haspopup="dialog">
          {SCOPE_TEXT.pick}
        </button>
        <PickedTag />
      </div>
    </div>
  )
}

/**
 * What is picked, beside the main page's region button: a white pill with a violet rim, 「강서구 거주」 for the first
 * 거주 (else the first 직장, 「금천구 재직」), and 「…」 right of it when two regions or more are set; nothing before any (user
 * 2026-10-20: "오른쪽에 흰 버튼 … 강서구 거주 이런것보이게 하고 2개 이상되면 .. 이거 추가해줘"). It only shows.
 */
function PickedTag() {
  const { regions } = useRegions()
  const labels = useRegionLabels(useRegionTree().data)
  const residence = regions.residence[0]
  const id = residence ?? regions.work[0]
  const label = id === undefined ? undefined : labels.get(id)
  if (label === undefined) return null
  const place = label.split(' ').slice(1).join(' ') || label
  const word = residence !== undefined ? SCOPE_TEXT.residenceShort : SCOPE_TEXT.workShort
  const more = regions.residence.length + regions.work.length >= 2
  return (
    <>
      {/* shows only; a tap does nothing (user 2026-10-20: "이거 버튼 눌러도 이벤트 X") */}
      <span className={styles.pickedTag}>{`${place} ${word}`}</span>
      {/* outside the pill, right of it, a 「+」 (user 2026-10-20: ".. 이건 네모칸 밖에 우측에", "... 말고 + 기호로") */}
      {more && (
        <span className={styles.pickedMore} aria-label="외 더 있음">
          +
        </span>
      )}
    </>
  )
}

interface SheetProps {
  open: boolean
  onOpenChange: (open: boolean) => void
}

/**
 * Phones (user 2026-10-19: "사는 곳 일하는 곳 고르기는 그것만 있고 설정에는 없었으면 해"): the chip's own bottom sheet,
 * "찾을 도서관" with the box's body only, the same shared sheet as ⚙ (handle, ⓧ, back, a tap outside).
 */
export function LibraryScopeSheet({ open, onOpenChange }: SheetProps) {
  return (
    <BottomSheet open={open} onOpenChange={onOpenChange} title={SCOPE_TEXT.sheetTitle} closeButton closeOnBack>
      <div className={styles.sheet}>
        <LibraryScopeBody onLeave={() => onOpenChange(false)} sheet />
      </div>
    </BottomSheet>
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
 * "온라인에서 가입 가능한 곳만 보기" (R8-1; a switch with its small line since user 2026-10-19, the checkbox "직접 가야
 * 가입되는 도서관은 빼기" before): in the box and the phone "찾을 도서관" sheet; on = `visit=none`, only kept for the next
 * search.
 */
export function VisitSwitch({
  visit,
  onChange,
  note = true,
}: {
  visit: VisitFilter
  onChange: (visit: VisitFilter) => void
  /** The grey line under the title (left out on the phone sheet, user 2026-10-20). */
  note?: boolean
}) {
  const titleId = useId()
  const noteId = useId()
  return (
    <label className={styles.visit}>
      <span className={styles.switch}>
        <input
          type="checkbox"
          role="switch"
          className={styles.switchInput}
          checked={visit === 'none'}
          aria-labelledby={titleId}
          aria-describedby={note ? noteId : undefined}
          onChange={(event) => onChange(event.target.checked ? 'none' : 'any')}
        />
        <span className={styles.switchTrack} aria-hidden="true" />
      </span>
      <span className={styles.visitText}>
        <span id={titleId} className={styles.visitTitle}>
          {SCOPE_TEXT.visitNone}
        </span>
        {note && (
          <span id={noteId} className={styles.visitNote}>
            {SCOPE_TEXT.visitNote}
          </span>
        )}
      </span>
    </label>
  )
}

interface BodyProps {
  /** The my page link was followed. */
  onLeave?: () => void
  /** Phones: inside the "찾을 도서관" sheet, the grid takes the screen's width. */
  sheet?: boolean
  /** PC and tablets: the box under the card (no numbers, the card beside shows them). */
  compact?: boolean
}

/** Regions the main page's box takes (user 2026-10-20: one). */
export const MAIN_REGIONS = 1

/**
 * The main page's box and phone sheet (user 2026-10-20: "메인에서 지역 선택은 지역 하나만 고를 수 있게 … 마이페이지에서 뭐
 * 이것저것 더 설정할 수 있다 아래 깔아두고"): one 거주 (× first to pick another) and a line to the my page for the
 * rest; no numbers (user 2026-10-20: "이거 빼줘", the card on the page has them). A check only saves, never a search.
 */
export function LibraryScopeBody({ onLeave, sheet = false, compact = false }: BodyProps) {
  const { regions, setRegions, source, notices, saveFailed } = useRegions()
  const [saved, setSaved] = useState(false)
  const residenceId = useId()

  // only the first 거주 is shown and changed here; the other regions, added libraries and settings stay as the my page
  // set them (user 2026-10-20: "메인은 그냥 맨 앞에 거주 조건만 바꾸는 걸로 해줘 지역 도서관 설정을 어떻게 하든")
  const first = regions.residence.slice(0, MAIN_REGIONS)
  const toggle = (id: number) => {
    const rest = regions.residence.slice(MAIN_REGIONS)
    const residence = first.includes(id) ? rest : [id, ...rest.filter((x) => x !== id)]
    const next: StoredRegions = { ...regions, residence }
    // a failure is on screen (saveFailed)
    setRegions(next).catch(() => undefined)
    setSaved(source === 'browser')
  }

  return (
    <div className={styles.body} data-sheet={sheet || undefined} data-compact={compact || undefined}>
      <div className={styles.part} role="group" aria-labelledby={residenceId}>
        <h3 id={residenceId} className={styles.partTitle}>
          {SCOPE_TEXT.residence}
        </h3>
        <div className={sheet ? styles.gridBleed : undefined}>
          <RegionGrid
            field="residence"
            value={first}
            onToggle={toggle}
            full={first.length >= MAIN_REGIONS}
            notices={notices}
            single
          />
        </div>
        <SaveHint saved={saved} />
      </div>
      {saveFailed && (
        <p className={styles.error} role="alert">
          {REGION_SAVE_FAILED}
        </p>
      )}
      <p className={styles.moreLine}>
        {SCOPE_TEXT.moreBefore}
        <Link to="/my-libraries" className={styles.strongLink} onClick={onLeave}>
          {SCOPE_TEXT.myPageWord}
        </Link>
        {SCOPE_TEXT.moreAfter}
      </p>
    </div>
  )
}

/**
 * The results, the book page and the picks (user 2026-10-20): what the search uses, no box. Without regions or added
 * libraries it says only libraries anyone may join are searched and links the my page; otherwise nothing. On a results
 * screen it asks for [검색] when the regions or the switch changed since its search.
 */
export function ScopeNote({
  regionsChanged = false,
  visitChanged = false,
  onSearchAgain,
}: {
  regionsChanged?: boolean
  visitChanged?: boolean
  onSearchAgain?: () => void
}) {
  const state = useLineState()
  const text = useScopeLineText()
  if (state === 'waiting') return <div className={styles.noteLine} aria-hidden="true" data-waiting />
  if (state === 'failed') {
    return (
      <div className={styles.noteLine}>
        <AccountFailed />
      </div>
    )
  }
  const hint = regionsChanged || visitChanged
  // with regions or added libraries no line at all (user 2026-10-20: "서울 강남구에 살아요 · 마이페이지에서 바꿀 수
  // 있어요 이거 문구 자체를 빼줘"), only the [검색] line when it applies
  if (text !== null && !hint) return null
  return (
    <div className={styles.noteLine}>
      {text === null && (
        <p className={styles.noteText}>
          {SCOPE_TEXT.noteNoneBefore}
          <Link to="/my-libraries" className={styles.strongLink}>
            {SCOPE_TEXT.myPageWord}
          </Link>
          {SCOPE_TEXT.noteNoneAfter}
        </p>
      )}
      {regionsChanged ? (
        <p className={styles.conditionHint}>
          <PressSearch before={SCOPE_TEXT.pickedBefore(null)} onSearch={onSearchAgain} />
        </p>
      ) : visitChanged ? (
        <p className={styles.conditionHint}>
          <PressSearch before={SCOPE_TEXT.visitChangedBefore} onSearch={onSearchAgain} />
        </p>
      ) : null}
    </div>
  )
}
