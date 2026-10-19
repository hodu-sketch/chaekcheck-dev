import { Popover } from 'radix-ui'
import { useState, type ReactNode } from 'react'
import { Link } from 'react-router'
import type { JoinSurvey, TargetLibrary } from '../../api/types'
import type { ClickScreen } from '../../api/usageEvents'
import { LibraryLogo } from '../../components/LibraryLogo'
import { libraryClick } from '../../lib/libraryClick'
import { classificationText } from '../../lib/libraryClassification'
import { useLibraryLink } from '../../lib/libraryLink'
import { safeHttpUrl } from '../../lib/safeUrl'
import { useMediaQuery } from '../../lib/useMediaQuery'
import { libraryGroup } from './resultGroups'
import { JoinGuide } from './JoinGuide'
import { type ResultRow as Row } from './sortBatch'
import styles from './ResultRow.module.css'

/** [확정] (cycle 08 handoff step 7-4): a holding that left the library's list (`holding_not_found`). */
export const HOLDING_GONE = '이 도서관 목록에서 빠졌어요'

/** [확정] (cycle 20 handoff 1-13): a row whose lookup failed; "확인 실패" before. */
export const LOOKUP_UNCHECKED = '대출 정보를 확인하지 못했어요'

/** The button's word (user 2026-10-19). */
export const RENT_LABEL = '대여하기'

/**
 * 「대여하기」 (user 2026-10-19): the one way from a result to the library's page (the whole card was the link before).
 * A new tab on a computer, the same tab on touch screens (`useLibraryLink`). Its name says the library:
 * "○○에서 대여하기".
 */
function RentLink({
  href,
  libraryName,
  report,
}: {
  href: string
  libraryName: string
  report: ReturnType<typeof libraryClick>
}) {
  const { newTab, props } = useLibraryLink()
  return (
    <a
      className={styles.rent}
      href={href}
      aria-label={`${libraryName}에서 ${RENT_LABEL}${newTab ? ' (새 창)' : ''}`}
      {...props}
      {...report}
    >
      {RENT_LABEL}
    </a>
  )
}

/**
 * A library of a direct search whose page could not be read (cycle 20 review #1 [확정]): one line, no book title,
 * 「○○도서관 · 대출 정보를 확인하지 못했어요」; 「대여하기」 at its end opens the library's site when it is known.
 */
export function UncheckedLibraryLine({
  library,
  url,
  screen,
}: {
  library: TargetLibrary
  url: string | null
  screen?: ClickScreen
}) {
  const href = safeHttpUrl(url)
  const click = screen ? { screen } : undefined
  return (
    <article className={`${styles.row} ${styles.libraryLine}`} data-group="unchecked">
      <p className={styles.libraryName}>
        <span className={styles.libraryLineName}>{library.name}</span>
        <span className={styles.libraryLineState}> · {LOOKUP_UNCHECKED}</span>
      </p>
      {href && <RentLink href={href} libraryName={library.name} report={libraryClick(library.id, 'result_card', click)} />}
    </article>
  )
}

/** [안] wording (user kept the handoff text, 2026-10-11). */
export const MOBILE_SITE_NOTE = '폰에서는 사이트가 다른 화면을 보여요. PC에서 열어 주세요'

interface Props {
  row: Row
  library: TargetLibrary | undefined
  searchUrl: string | undefined
  /** The screen showing this line, for the usage report of its links (cycle 15); none sends nothing. */
  screen?: ClickScreen
  /**
   * Cycle 22 R6 (ADR-0136): a pill after the library name ([직접 추가] / [내 지역] / [어디 살든 가입], plus amber
   * [방문 가입]; a library of ③ gets its eligibility summary in grey), each opening a short note. Off on the screens
   * without result groups (book page, picks).
   */
  tags?: boolean
  /** Which of the user's regions [내 지역] stands for (D2): the side(s) the search was made with. */
  regionBasis?: RegionBasis
}

/** The regions a search was made with, for the [내 지역] note (cycle 22 D2). */
export type RegionBasis = 'residence' | 'work' | 'both'

/** The note of [직접 추가] (cycle 22 문구표); the join pills open the join guide instead of a note (user 2026-10-19). */
export const TAG_TEXT = {
  chosen: '직접 추가한 도서관이에요',
  removeAtMyPage: '마이페이지에서 빼기',
} as const

/**
 * One book at one library: title / author · publisher / supplier / library + join condition / counts / state /
 * link. No description or cover. The state comes from the row's availability only (ADR-0019). No "구독형" tag by the
 * library name: the state already says "구독형 · 바로 대출" (user request 2026-10-05).
 */
export function ResultRow({ row, library, searchUrl, screen, tags = false }: Props) {
  const phone = useMediaQuery('(width <= 640px)')
  // the join guide's window; "가입 방법 보기" in a pill's note opens it too (cycle 22 R6-2)
  const [joinOpen, setJoinOpen] = useState(false)
  // a direct search has no book of ours; every other screen sends the row's book (spec R2-2)
  const click = screen ? { screen, bookId: screen === 'direct_results' ? undefined : row.bookId } : undefined
  const report = (place: Parameters<typeof libraryClick>[1]) => libraryClick(row.libraryId, place, click)
  // the book's own page; the library search page only when the site gave no book link
  const href = safeHttpUrl(row.detailUrl) ?? safeHttpUrl(searchUrl)
  // added by hand (cycle 06, ADR-0050): "직접 추가" in place of the group pill
  const chosen = library?.chosen === true
  const libraryName = library?.name ?? '도서관'
  // cycle 09: "교보 · 소장" on the line after the name, outside the name button; none from an older server
  const classification = library ? classificationText(library) : null
  // a library from the collected list whose join rules were not found or not researched (cycle 07, R6): its notice
  // in the join guide
  const surveyNotice = library ? SURVEY_NOTICE[library.joinSurvey] : undefined
  // cycle 08 R3: the site shows another page on phones; a note, never a blocked link (also when the check failed)
  const mobileNote = phone && library?.mobileSiteDiffers === true
  const state = row.failed ? (row.gone ? HOLDING_GONE : LOOKUP_UNCHECKED) : stateText(row)
  const unchecked = !row.gone && (row.failed === true || row.availability === 'UNKNOWN')
  const group =
    row.availability === 'AVAILABLE'
      ? 'borrow'
      : row.availability === 'PENDING'
        ? 'pending'
        : unchecked
          ? 'unchecked'
          : 'reserve'

  return (
    <article className={styles.row} data-group={group}>
      <div className={styles.book}>
        <h3 className={styles.title}>{row.title}</h3>
        <p className={styles.meta}>
          {[row.author, row.publisher].filter(Boolean).join(' · ') || '저자·출판사 정보 없음'}
          {row.supplier && <span className={styles.supplier}>{row.supplier}</span>}
        </p>
      </div>

      <div className={styles.library}>
        {/* the logo on its own line above the name (user request 2026-10-05); it opens the library's main page */}
        <div className={styles.logoLine}>
          <LibraryLogo
            name={libraryName}
            logoUrl={library?.logoUrl}
            homepageUrl={library?.homepageUrl}
            size={20}
            libraryId={row.libraryId}
            click={click}
          />
        </div>
        <p className={styles.libraryName}>
          {/*
            The library name opens the join guide on every width (user 2026-10-19; a small popover before): a window
            in the middle of the screen, the bottom sheet on phones.
          */}
          {library ? (
            <>
              <button
                type="button"
                className={styles.libraryButton}
                aria-label={`${libraryName} 가입 정보`}
                aria-haspopup="dialog"
                data-open={joinOpen || undefined}
                onClick={() => setJoinOpen(true)}
              >
                {libraryName}
              </button>
              <JoinGuide
                open={joinOpen}
                onOpenChange={setJoinOpen}
                library={library}
                bookUrl={href}
                surveyNotice={surveyNotice}
                mobileNote={mobileNote ? MOBILE_SITE_NOTE : undefined}
                report={report}
              />
            </>
          ) : (
            <span>{libraryName}</span>
          )}
          {tags && library ? (
            <RowTags library={library} onJoin={() => setJoinOpen(true)} />
          ) : (
            chosen && <span className={styles.chosen}>직접 추가</span>
          )}
        </p>
        {classification && <p className={styles.classification}>{classification}</p>}
        {mobileNote && <p className={styles.mobileNote}>{MOBILE_SITE_NOTE}</p>}
      </div>

      <div className={styles.stock}>
        {!row.failed && row.availabilityBasis === 'SUBSCRIPTION' ? (
          // the subscription caution opens from the state instead of taking a line (user request 2026-10-05)
          <Popover.Root>
            <Popover.Trigger className={`${styles.state} ${styles.stateButton}`}>
              {state} <span aria-hidden="true">ⓘ</span>
            </Popover.Trigger>
            <Popover.Portal>
              <Popover.Content className={styles.statePopover} side="bottom" align="end" sideOffset={6} collisionPadding={12}>
                <p className={styles.note}>월 예산 소진 시 이용 불가</p>
                <Popover.Arrow className={styles.popoverArrow} width={12} height={6} />
              </Popover.Content>
            </Popover.Portal>
          </Popover.Root>
        ) : (
          state !== null && <p className={styles.state}>{state}</p>
        )}
        <div className={styles.stockLine}>
          <Counts row={row} />
        </div>
        {/* the card itself no longer opens anything (user 2026-10-19): 「대여하기」 under the counts, full width on phones */}
        {href && <RentLink href={href} libraryName={libraryName} report={report('result_card')} />}
      </div>
    </article>
  )
}
/**
 * The pills after the library name (cycle 22 R6-1): one of [직접 추가] / [내 지역] / [어디 살든 가입] by the library's
 * group (D3'), plus [방문 가입] when the library has to be visited to join; a library of ③ shows its eligibility summary
 * in grey instead, without a note.
 */
function RowTags({ library, onJoin }: { library: TargetLibrary; onJoin: () => void }) {
  const group = libraryGroup(library)
  return (
    <>
      {library.chosen ? (
        <TagPill
          label="직접 추가"
          tone="chosen"
          text={TAG_TEXT.chosen}
          action={() => (
            <Link to="/my-libraries" className={styles.tagAction}>
              {TAG_TEXT.removeAtMyPage}
            </Link>
          )}
        />
      ) : group === 'MINE' ? (
        <TagPill label="내 지역" tone="mine" onPress={onJoin} />
      ) : group === 'OPEN' ? (
        <TagPill label="어디 살든 가입" tone="open" onPress={onJoin} />
      ) : (
        library.eligibilitySummary && <span className={styles.tagSummary}>{library.eligibilitySummary}</span>
      )}
      {library.visitRequired && <TagPill label="방문 가입" tone="visit" onPress={onJoin} />}
    </>
  )
}

/**
 * One pill. A join pill ({@code onPress}) is a plain button that opens the join guide (user 2026-10-19: "가입 방법 보기 안
 * 눌러도 그냥 바로 가입방법 안내로"); the others open their note (radix Popover) on a tap or click. Nothing opens when the
 * mouse rests on a pill (user 2026-10-19: "마우스 올리면 뭐 설명 뜨는데 다 없애줘"). A click outside or Esc closes the note.
 */
function TagPill({
  label,
  tone,
  text,
  action,
  onPress,
}: {
  label: string
  tone: 'chosen' | 'mine' | 'open' | 'visit'
  text?: string
  action?: (close: () => void) => ReactNode
  /** A tap or click opens the join guide; the pill has no note. */
  onPress?: () => void
}) {
  const phone = useMediaQuery('(width <= 640px)')
  const [open, setOpen] = useState(false)
  if (onPress) {
    return (
      <button type="button" className={styles.tag} data-tone={tone} onClick={onPress}>
        {label}
      </button>
    )
  }
  return (
    <Popover.Root open={open} onOpenChange={setOpen}>
      <Popover.Trigger className={styles.tag} data-tone={tone}>
        {label}
      </Popover.Trigger>
      <Popover.Portal>
        <Popover.Content
          className={styles.tagPopover}
          side={phone ? 'bottom' : 'top'}
          align="start"
          sideOffset={6}
          collisionPadding={12}
        >
          <p className={styles.tagNote}>
            {text} · {action?.(() => setOpen(false))}
          </p>
          <Popover.Arrow className={styles.popoverArrow} width={12} height={6} />
        </Popover.Content>
      </Popover.Portal>
    </Popover.Root>
  )
}

/** The join notice of a library whose join rules were not found or not researched (cycle 07, spec R6) [확정]. */
const SURVEY_NOTICE: Record<JoinSurvey, string | undefined> = {
  SURVEYED: undefined,
  NOT_FOUND: '가입 조건을 찾지 못했어요. 도서관 사이트에서 확인해 주세요',
  MEMBERS_ONLY: '학교·회사 같은 기관의 구성원용으로 보여요. 이용할 수 있는지는 그 기관에 확인해 주세요',
  NOT_SURVEYED: '가입 정보를 아직 조사하지 않았어요',
}

/**
 * The state box, or `null` when the group title and the counts already say it: no "지금 대출 가능" and no "예약 대기 N명"
 * (user request 2026-10-05).
 */
function stateText(row: Row): string | null {
  switch (row.availability) {
    case 'AVAILABLE':
      if (row.availabilityBasis === 'SUBSCRIPTION') return '구독형 · 바로 대출'
      return null
    case 'RESERVABLE':
      if (row.holdings === null) return '대출 중 · 예약'
      return null
    case 'PENDING':
      return '대출 정보 조회 중'
    case 'UNKNOWN':
      return '대출 가능 여부 확인 필요'
  }
}

/** Only the counts the site shows (portals show no holdings; subscription sites show none). */
function Counts({ row }: { row: Row }) {
  const counts: Array<[string, number]> = []
  if (row.holdings !== null) counts.push(['보유', row.holdings])
  if (row.loaned !== null) counts.push(['대출 중', row.loaned])
  if (row.reservations !== null) counts.push(['예약', row.reservations])
  if (counts.length === 0) return null
  return (
    <dl className={styles.counts}>
      {counts.map(([label, value]) => (
        <div key={label}>
          <dt>{label}</dt>
          <dd>{value}</dd>
        </div>
      ))}
    </dl>
  )
}
