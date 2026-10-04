import { Popover } from 'radix-ui'
import type { TargetLibrary } from '../../api/types'
import { safeHttpUrl } from '../../lib/safeUrl'
import { useMediaQuery } from '../../lib/useMediaQuery'
import { needsJoinCheck, type ResultRow as Row } from './sortBatch'
import styles from './ResultRow.module.css'

interface Props {
  row: Row
  library: TargetLibrary | undefined
  searchUrl: string | undefined
}

/**
 * One book at one library: title / author · publisher / supplier / library + join condition / counts / state /
 * link. No description or cover. The state comes from the row's availability only (ADR-0019). No "구독형" tag by the
 * library name: the state already says "구독형 · 바로 대출" (user request 2026-10-05).
 */
export function ResultRow({ row, library, searchUrl }: Props) {
  const phone = useMediaQuery('(width <= 640px)')
  // the book's own page; the library search page only when the site gave no book link
  const href = safeHttpUrl(row.detailUrl) ?? safeHttpUrl(searchUrl)
  // an extra condition (e.g. 책이음) next to a matching region is only extra text (AC-12, user decision 2026-09-29)
  // added by hand (cycle 06, ADR-0050): "직접 추가" in place of the eligibility badge; the join details are the same
  const chosen = library?.chosen === true
  const joinCheck = !chosen && needsJoinCheck(library?.eligibility)
  const joinCondition =
    library?.priorityGroup === 'OPEN' ? '누구나 가입' : (library?.eligibilitySummary ?? '가입 자격 확인 필요')
  const joinMethod = library?.joinMethod ?? '-'
  const joinUrl = safeHttpUrl(library?.joinUrl)
  // no known join page yet: the library's e-library front page, where its sign-up guide can be found
  const siteUrl = joinUrl ? undefined : siteOrigin(searchUrl)
  const libraryName = library?.name ?? '도서관'
  const group = row.availability === 'AVAILABLE' ? 'borrow' : row.availability === 'PENDING' ? 'pending' : 'reserve'

  const conditionLines = (
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
          <a className={styles.joinLink} href={joinUrl} target="_blank" rel="noopener noreferrer">
            {joinMethod}
            <span className="visually-hidden"> (새 창)</span>
          </a>
        ) : (
          joinMethod
        )}
      </p>
      {siteUrl && (
        <p className={styles.join}>
          <a className={styles.joinLink} href={siteUrl} target="_blank" rel="noopener noreferrer">
            전자도서관에서 가입 안내 보기
            <span className="visually-hidden"> (새 창)</span>
          </a>
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
        <p className={styles.libraryName}>
          {/* the library name opens the join details on every width (user requests 2026-10-05; U-12 before) */}
          <Popover.Root>
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
          {chosen && <span className={styles.chosen}>직접 추가</span>}
        </p>
      </div>

      <div className={styles.stock}>
        {!row.failed && row.availabilityBasis === 'SUBSCRIPTION' ? (
          // the subscription caution opens from the state instead of taking a line (user request 2026-10-05)
          <Popover.Root>
            <Popover.Trigger className={`${styles.state} ${styles.stateButton}`}>
              {stateText(row)} <span aria-hidden="true">ⓘ</span>
            </Popover.Trigger>
            <Popover.Portal>
              <Popover.Content className={styles.joinPopover} side="bottom" align="end" sideOffset={6} collisionPadding={12}>
                <p className={styles.note}>월 예산 소진 시 이용 불가</p>
                <Popover.Arrow className={styles.joinPopoverArrow} width={12} height={6} />
              </Popover.Content>
            </Popover.Portal>
          </Popover.Root>
        ) : (
          <p className={styles.state}>{row.failed ? '확인 실패' : stateText(row)}</p>
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
        <a className={styles.cardLink} href={href} target="_blank" rel="noopener noreferrer">
          <span className="visually-hidden">
            {libraryName}에서 보기 (새 창)
          </span>
        </a>
      )}
    </article>
  )
}

/** Scheme + host (+ port) of the library's search page, i.e. its e-library front page. */
function siteOrigin(searchUrl: string | undefined): string | undefined {
  const safe = safeHttpUrl(searchUrl)
  return safe ? new URL(safe).origin + '/' : undefined
}

function stateText(row: Row): string {
  switch (row.availability) {
    case 'AVAILABLE':
      if (row.availabilityBasis === 'SUBSCRIPTION') return '구독형 · 바로 대출'
      // no "N권": the counts beside it already say it (user request 2026-10-05)
      return '지금 대출 가능'
    case 'RESERVABLE':
      if (row.holdings === null) return '대출 중 · 예약'
      return `예약 대기 ${row.reservations ?? 0}명`
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
