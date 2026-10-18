import { Popover } from 'radix-ui'
import { useEffect, useRef, useState, type PointerEvent, type ReactNode } from 'react'
import { Link } from 'react-router'
import type { JoinSurvey, TargetLibrary } from '../../api/types'
import type { ClickScreen } from '../../api/usageEvents'
import { LibraryLogo } from '../../components/LibraryLogo'
import { libraryClick } from '../../lib/libraryClick'
import { classificationText } from '../../lib/libraryClassification'
import { safeHttpUrl } from '../../lib/safeUrl'
import { useMediaQuery } from '../../lib/useMediaQuery'
import { QUEUED_LABEL } from '../lookupstatus/lookupStatusText'
import { useLookupStatus } from '../lookupstatus/LookupStatusProvider'
import { libraryGroup } from './resultGroups'
import { needsJoinCheck, type ResultRow as Row } from './sortBatch'
import styles from './ResultRow.module.css'

/** [확정] (cycle 08 handoff step 7-4): a holding that left the library's list (`holding_not_found`). */
export const HOLDING_GONE = '이 도서관 목록에서 빠졌어요'

/** [확정] (cycle 20 handoff 1-13): a row whose lookup failed; "확인 실패" before. */
export const LOOKUP_UNCHECKED = '대출 정보를 확인하지 못했어요'

/**
 * Whether the row carries 「지금 이 도서관 조회가 밀려요」 (cycle 20 spec R4, review #5 [확정]): only while the row is
 * still being checked, or in 「확인하지 못한 도서관」; never on a row that answered. A row that was marked when its lookup
 * failed keeps the mark until the next search (the row is unmounted then), whatever the later status answers say.
 */
function useQueuedMark(libraryId: number, checking: boolean, unchecked: boolean): boolean {
  const { queued } = useLookupStatus()
  const kept = useRef(false)
  const now = queued.has(libraryId)
  if (unchecked && now) kept.current = true
  return (unchecked && kept.current) || (checking && now)
}

/** The small amber tag 「지금 이 도서관 조회가 밀려요」: next to the name on wide screens, under it on phones. */
function QueuedTag() {
  return <span className={styles.queued}>{QUEUED_LABEL}</span>
}

/**
 * A library of a direct search whose page could not be read (cycle 20 review #1 [확정]): one line, no book title,
 * 「○○도서관 · 대출 정보를 확인하지 못했어요」; the line opens the library's site when it is known.
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
  const queued = useQueuedMark(library.id, false, true)
  const href = safeHttpUrl(url)
  const click = screen ? { screen } : undefined
  return (
    <article className={`${styles.row} ${styles.libraryLine}`} data-group="unchecked" data-link={href ? true : undefined}>
      <p className={styles.libraryName}>
        <span className={styles.libraryLineName}>{library.name}</span>
        <span className={styles.libraryLineState}> · {LOOKUP_UNCHECKED}</span>
        {queued && <QueuedTag />}
      </p>
      {href && (
        <a
          className={styles.cardLink}
          href={href}
          target="_blank"
          rel="noopener noreferrer"
          {...libraryClick(library.id, 'result_card', click)}
        >
          <span className="visually-hidden">{library.name}에서 보기 (새 창)</span>
        </a>
      )}
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

/** The notes of the pills (cycle 22 문구표 말풍선, R6-2, D1, D2) [확정]. */
export const TAG_TEXT = {
  open: '그 지역에 살지 않아도 온라인으로 가입할 수 있어요',
  visit: (name: string) => `가입하려면 ${name}에 한 번 가야 해요`,
  mine: { residence: '사는 곳으로', work: '일하는 곳으로', both: '사는 곳이나 일하는 곳으로' } as Record<RegionBasis, string>,
  mineTail: ' 가입할 수 있어요',
  chosen: '직접 추가한 도서관이에요',
  howToJoin: '가입 방법 보기',
  removeAtMyPage: '마이페이지에서 빼기',
} as const

/**
 * One book at one library: title / author · publisher / supplier / library + join condition / counts / state /
 * link. No description or cover. The state comes from the row's availability only (ADR-0019). No "구독형" tag by the
 * library name: the state already says "구독형 · 바로 대출" (user request 2026-10-05).
 */
export function ResultRow({ row, library, searchUrl, screen, tags = false, regionBasis = 'both' }: Props) {
  const phone = useMediaQuery('(width <= 640px)')
  // controlled, so "가입 방법 보기" in a pill's note can open it (cycle 22 R6-2)
  const [joinOpen, setJoinOpen] = useState(false)
  // a direct search has no book of ours; every other screen sends the row's book (spec R2-2)
  const click = screen ? { screen, bookId: screen === 'direct_results' ? undefined : row.bookId } : undefined
  const report = (place: Parameters<typeof libraryClick>[1]) => libraryClick(row.libraryId, place, click)
  // the book's own page; the library search page only when the site gave no book link
  const href = safeHttpUrl(row.detailUrl) ?? safeHttpUrl(searchUrl)
  // an extra condition (e.g. 책이음) next to a matching region is only extra text (AC-12, user decision 2026-09-29)
  // added by hand (cycle 06, ADR-0050): "직접 추가" in place of the eligibility badge; the join details are the same
  const chosen = library?.chosen === true
  const joinCheck = !chosen && needsJoinCheck(library?.eligibility)
  const joinCondition =
    library?.priorityGroup === 'OPEN' ? OPEN_JOIN_TEXT : (library?.eligibilitySummary ?? '가입 자격 확인 필요')
  const joinMethod = library?.joinMethod ?? '-'
  const joinUrl = safeHttpUrl(library?.joinUrl)
  // no known join page yet: the library's e-library front page, where its sign-up guide can be found
  const siteUrl = joinUrl ? undefined : siteOrigin(searchUrl)
  const libraryName = library?.name ?? '도서관'
  // cycle 09: "교보 · 소장" on the line after the name, outside the name button; none from an older server
  const classification = library ? classificationText(library) : null
  // a library from the collected list whose join rules were not found or not researched (cycle 07, R6): its notice
  // instead
  const surveyNotice = library ? SURVEY_NOTICE[library.joinSurvey] : undefined
  const homepageUrl = safeHttpUrl(library?.homepageUrl)
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
  const queued = useQueuedMark(row.libraryId, row.availability === 'PENDING', unchecked)

  const conditionLines = surveyNotice ? (
    <>
      <p className={styles.surveyNotice}>{surveyNotice}</p>
      {library?.joinMethod && <p className={styles.join}>가입: {library.joinMethod}</p>}
      {library?.joinCaution && (
        <p className={styles.caution}>
          <span className={styles.cautionMark}>주의</span> {library.joinCaution}
        </p>
      )}
      {homepageUrl && (
        <p className={styles.join}>
          <a
            className={styles.joinLink}
            href={homepageUrl}
            target="_blank"
            rel="noopener noreferrer"
            {...report('home_page')}
          >
            도서관 첫 화면
            <span className="visually-hidden"> (새 창)</span>
          </a>
          {mobileNote && <span className={styles.mobileNote}>{MOBILE_SITE_NOTE}</span>}
        </p>
      )}
    </>
  ) : (
    <>
      {/* "가입 자격 확인 필요" is in here too, not next to the name (user request 2026-10-05) */}
      {joinCheck && joinCondition !== '가입 자격 확인 필요' && (
        <p>
          <span className={styles.badge}>가입 자격 확인 필요</span>
        </p>
      )}
      <p className={styles.condition}>
        {joinCondition}
        {library?.visitRequired && <span className={styles.visit}>방문 가입</span>}
      </p>
      {library?.extraConditionText && <p className={styles.extra}>{library.extraConditionText}</p>}
      {library?.joinNotice && (
        <p className={styles.notice}>
          <span className={styles.noticeMark}>안내</span> {library.joinNotice}
        </p>
      )}
      {library?.joinCaution && (
        <p className={styles.caution}>
          <span className={styles.cautionMark}>주의</span> {library.joinCaution}
        </p>
      )}
      <p className={styles.join}>
        가입:{' '}
        {joinUrl ? (
          <a className={styles.joinLink} href={joinUrl} target="_blank" rel="noopener noreferrer" {...report('join_link')}>
            {joinMethod}
            <span className="visually-hidden"> (새 창)</span>
          </a>
        ) : (
          joinMethod
        )}
      </p>
      {siteUrl && (
        <p className={styles.join}>
          <a className={styles.joinLink} href={siteUrl} target="_blank" rel="noopener noreferrer" {...report('join_guide')}>
            전자도서관에서 가입 안내 보기
            <span className="visually-hidden"> (새 창)</span>
          </a>
          {mobileNote && <span className={styles.mobileNote}>{MOBILE_SITE_NOTE}</span>}
        </p>
      )}
    </>
  )

  return (
    <article className={styles.row} data-group={group} data-link={href ? true : undefined}>
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
          {/* the library name opens the join details on every width (user requests 2026-10-05; U-12 before) */}
          <Popover.Root open={joinOpen} onOpenChange={setJoinOpen}>
            <Popover.Trigger className={styles.libraryButton} aria-label={`${libraryName} 가입 정보`}>
              {libraryName}
            </Popover.Trigger>
            <Popover.Portal>
              {/* to the right of the name (user request 2026-10-05); below it on phones, where there is no room */}
              <Popover.Content
                className={styles.joinPopover}
                side={phone ? 'bottom' : 'right'}
                align="start"
                sideOffset={phone ? 6 : 10}
                collisionPadding={12}
              >
                <div className={styles.joinPopoverBody}>{conditionLines}</div>
                <Popover.Arrow className={styles.joinPopoverArrow} width={12} height={6} />
              </Popover.Content>
            </Popover.Portal>
          </Popover.Root>
          {tags && library ? (
            <RowTags library={library} regionBasis={regionBasis} onJoin={() => setJoinOpen(true)} />
          ) : (
            chosen && <span className={styles.chosen}>직접 추가</span>
          )}
          {queued && <QueuedTag />}
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
              <Popover.Content className={styles.joinPopover} side="bottom" align="end" sideOffset={6} collisionPadding={12}>
                <p className={styles.note}>월 예산 소진 시 이용 불가</p>
                <Popover.Arrow className={styles.joinPopoverArrow} width={12} height={6} />
              </Popover.Content>
            </Popover.Portal>
          </Popover.Root>
        ) : (
          state !== null && <p className={styles.state}>{state}</p>
        )}
        <div className={styles.stockLine}>
          <Counts row={row} />
        </div>
      </div>

      {/*
        The whole card opens the library's page, no separate button (user request 2026-10-05). The link covers the card;
        the library name and the subscription state stay above it and open their own notes.
      */}
      {href && (
        <a className={styles.cardLink} href={href} target="_blank" rel="noopener noreferrer" {...report('result_card')}>
          <span className="visually-hidden">
            {libraryName}에서 보기 (새 창)
          </span>
        </a>
      )}
    </article>
  )
}

/**
 * The join details' eligibility summary of a library joinable wherever one lives (cycle 22 R6-5, D9; "누구나 가입"
 * before), on every screen.
 */
export const OPEN_JOIN_TEXT = '어디 살든 온라인 가입'

/**
 * The pills after the library name (cycle 22 R6-1): one of [직접 추가] / [내 지역] / [어디 살든 가입] by the library's
 * group (D3'), plus [방문 가입] when the library has to be visited to join; a library of ③ shows its eligibility summary
 * in grey instead, without a note.
 */
function RowTags({
  library,
  regionBasis,
  onJoin,
}: {
  library: TargetLibrary
  regionBasis: RegionBasis
  onJoin: () => void
}) {
  const group = libraryGroup(library)
  const join = (close: () => void) => (
    <button
      type="button"
      className={styles.tagAction}
      onClick={() => {
        close()
        onJoin()
      }}
    >
      {TAG_TEXT.howToJoin}
    </button>
  )
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
        <TagPill
          label="내 지역"
          tone="mine"
          text={`${TAG_TEXT.mine[regionBasis]}${TAG_TEXT.mineTail}`}
          action={join}
          closesForJoin
        />
      ) : group === 'OPEN' ? (
        <TagPill label="어디 살든 가입" tone="open" text={TAG_TEXT.open} action={join} closesForJoin />
      ) : (
        library.eligibilitySummary && <span className={styles.tagSummary}>{library.eligibilitySummary}</span>
      )}
      {library.visitRequired && (
        <TagPill label="방문 가입" tone="visit" text={TAG_TEXT.visit(library.name)} action={join} closesForJoin />
      )}
    </>
  )
}

/**
 * One pill and its note (radix Popover, like the join details): a tap or click opens it; on a computer the mouse
 * resting on it opens it too, and leaving the pill and the note closes it again unless it was clicked open. A click
 * outside or Esc closes it.
 */
function TagPill({
  label,
  tone,
  text,
  action,
  closesForJoin = false,
}: {
  label: string
  tone: 'chosen' | 'mine' | 'open' | 'visit'
  text: string
  action: (close: () => void) => ReactNode
  /** The action opens the join details: the focus is not given back to the pill, which would close them again. */
  closesForJoin?: boolean
}) {
  const phone = useMediaQuery('(width <= 640px)')
  const [open, setOpen] = useState(false)
  // opened by the mouse resting on it (it closes when the mouse leaves), not by a click
  const byHover = useRef(false)
  const toJoin = useRef(false)
  const closeTimer = useRef<number | undefined>(undefined)
  const cancelClose = () => window.clearTimeout(closeTimer.current)
  const scheduleClose = () => {
    cancelClose()
    closeTimer.current = window.setTimeout(() => {
      if (!byHover.current) return
      byHover.current = false
      setOpen(false)
    }, 150)
  }
  useEffect(() => () => window.clearTimeout(closeTimer.current), [])
  // a computer's mouse only; a phone's tap (or a touch screen reporting hover) opens it by the click
  const mouse = (event: PointerEvent) => event.pointerType === 'mouse' && !phone
  const close = () => {
    toJoin.current = closesForJoin
    byHover.current = false
    setOpen(false)
  }

  return (
    <Popover.Root
      open={open}
      onOpenChange={(next) => {
        byHover.current = false
        setOpen(next)
      }}
    >
      <Popover.Trigger
        className={styles.tag}
        data-tone={tone}
        onPointerEnter={(event) => {
          if (!mouse(event)) return
          cancelClose()
          if (!open) {
            byHover.current = true
            setOpen(true)
          }
        }}
        onPointerLeave={(event) => {
          if (mouse(event)) scheduleClose()
        }}
        onClick={(event) => {
          // a click on a note the mouse opened keeps it open (radix would toggle it shut)
          if (open && byHover.current) {
            event.preventDefault()
            byHover.current = false
          }
        }}
      >
        {label}
      </Popover.Trigger>
      <Popover.Portal>
        <Popover.Content
          className={styles.tagPopover}
          side={phone ? 'bottom' : 'top'}
          align="start"
          sideOffset={6}
          collisionPadding={12}
          onPointerEnter={cancelClose}
          onPointerLeave={(event) => {
            if (mouse(event)) scheduleClose()
          }}
          onOpenAutoFocus={(event) => {
            if (byHover.current) event.preventDefault()
          }}
          onCloseAutoFocus={(event) => {
            if (toJoin.current) event.preventDefault()
            toJoin.current = false
          }}
        >
          <p className={styles.tagNote}>
            {text} · {action(close)}
          </p>
          <Popover.Arrow className={styles.joinPopoverArrow} width={12} height={6} />
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

/** Scheme + host (+ port) of the library's search page, i.e. its e-library front page. */
function siteOrigin(searchUrl: string | undefined): string | undefined {
  const safe = safeHttpUrl(searchUrl)
  return safe ? new URL(safe).origin + '/' : undefined
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
