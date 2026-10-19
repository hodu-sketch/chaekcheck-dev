import { Dialog } from 'radix-ui'
import type { ReactNode } from 'react'
import type { TargetLibrary } from '../../api/types'
import type { ClickPlace } from '../../api/usageEvents'
import { BottomSheet } from '../../components/BottomSheet'
import type { libraryClick } from '../../lib/libraryClick'
import { safeHttpUrl } from '../../lib/safeUrl'
import { useMediaQuery } from '../../lib/useMediaQuery'
import { JOIN_GUIDE_TEXT as T, joinPrepare, joinSteps, joinTarget, joinWay } from './joinGuideParts'
import styles from './JoinGuide.module.css'

interface Props {
  open: boolean
  onOpenChange: (open: boolean) => void
  library: TargetLibrary
  /** The book at the library (the row's own card link); "이미 회원이에요 · 책 보기" is left out without it. */
  bookUrl: string | undefined
  /** The notice of a library whose join rules were not found or not researched (cycle 07, R6). */
  surveyNotice?: string
  /** A note for the phone site (cycle 08 R3), shown above the footer. */
  mobileNote?: string
  /** The usage report of a link in the window (cycle 15), as the row's links send it. */
  report: (place: ClickPlace) => ReturnType<typeof libraryClick>
}

/**
 * The join guide of one library (user 2026-10-19, in place of the small join popover): a window in the middle of the
 * screen on PC and tablets, the shared bottom sheet on phones. Eligibility by our own category, how to join, what to
 * bring, the join method's steps, then the links to the book and to the library's official join guide.
 */
export function JoinGuide({ open, onOpenChange, library, bookUrl, surveyNotice, mobileNote, report }: Props) {
  const phone = useMediaQuery('(width <= 640px)')
  const title = T.title(library.name)

  if (phone) {
    return (
      <BottomSheet open={open} onOpenChange={onOpenChange} title={title} closeButton closeOnBack>
        <div className={styles.sheet} data-testid="join-guide">
          <JoinGuideBody library={library} bookUrl={bookUrl} surveyNotice={surveyNotice} mobileNote={mobileNote} report={report}>
            {/* the sheet's name is read from its hidden title; this line only shows it */}
            <h2 className={styles.title} aria-hidden="true">
              {title}
            </h2>
          </JoinGuideBody>
        </div>
      </BottomSheet>
    )
  }

  return (
    <Dialog.Root open={open} onOpenChange={onOpenChange}>
      <Dialog.Portal>
        <Dialog.Overlay className={styles.overlay} />
        <Dialog.Content className={styles.box} aria-describedby={undefined} data-testid="join-guide">
          <Dialog.Close className={styles.close} aria-label="닫기">
            <svg width="14" height="14" viewBox="0 0 14 14" aria-hidden="true" focusable="false">
              <path d="M2 2l10 10M12 2L2 12" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" />
            </svg>
          </Dialog.Close>
          <JoinGuideBody library={library} bookUrl={bookUrl} surveyNotice={surveyNotice} mobileNote={mobileNote} report={report}>
            <Dialog.Title className={styles.title}>{title}</Dialog.Title>
          </JoinGuideBody>
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  )
}

function JoinGuideBody({
  library,
  bookUrl,
  surveyNotice,
  mobileNote,
  report,
  children,
}: Omit<Props, 'open' | 'onOpenChange'> & { children: ReactNode }) {
  const prepare = joinPrepare(library.joinMethod)
  const steps = joinSteps(library.joinMethod)
  const book = safeHttpUrl(bookUrl)
  const joinUrl = safeHttpUrl(library.joinUrl)
  const official = joinUrl ?? safeHttpUrl(library.homepageUrl)
  // the join page reports as the old join link; the front page in its place as the old guide or front page link did
  const officialPlace: ClickPlace = joinUrl ? 'join_link' : library.joinSurvey === 'SURVEYED' ? 'join_guide' : 'home_page'

  return (
    <>
      <div className={styles.inner}>
        <p className={styles.eyebrow}>{T.eyebrow}</p>
        {children}
        <p className={styles.lead}>{T.lead}</p>

        <dl className={styles.facts}>
          <dt>{T.target}</dt>
          <dd>
            {joinTarget(library)}
            {library.visitRequired && <span className={styles.visit}>{T.visit}</span>}
          </dd>
          <dt>{T.way}</dt>
          <dd>{joinWay(library)}</dd>
          {prepare && (
            <>
              <dt>{T.prepare}</dt>
              <dd>{prepare}</dd>
            </>
          )}
        </dl>

        {steps.kind === 'steps' ? (
          <ol className={styles.steps}>
            {steps.steps.map((step, i) => (
              <li key={i}>
                <span className={styles.stepNo} aria-hidden="true">
                  {i + 1}
                </span>
                <span>{step}</span>
              </li>
            ))}
          </ol>
        ) : (
          <p className={styles.stepText}>{steps.text}</p>
        )}

        {(surveyNotice || library.extraConditionText || library.joinCaution || library.joinNotice) && (
          <div className={styles.extras}>
            {surveyNotice && <p className={styles.surveyNotice}>{surveyNotice}</p>}
            {library.extraConditionText && <p className={styles.extra}>{library.extraConditionText}</p>}
            {library.joinCaution && (
              <p className={styles.line}>
                <span className={styles.cautionMark}>유의</span> {library.joinCaution}
              </p>
            )}
            {library.joinNotice && (
              <p className={styles.line}>
                <span className={styles.noticeMark}>안내</span> {library.joinNotice}
              </p>
            )}
          </div>
        )}

        <p className={styles.note}>{T.changes}</p>

        {book && (
          <a className={styles.member} href={book} target="_blank" rel="noopener noreferrer" {...report('result_card')}>
            {T.member} <span aria-hidden="true">↗</span>
            <span className="visually-hidden"> (새 창)</span>
          </a>
        )}
        {mobileNote && <p className={styles.mobileNote}>{mobileNote}</p>}
      </div>

      <div className={styles.footer}>
        <p className={styles.footerText}>
          {T.footer[0]}
          <br />
          {T.footer[1]}
        </p>
        {official && (
          <a className={styles.official} href={official} target="_blank" rel="noopener noreferrer" {...report(officialPlace)}>
            {T.official} <span aria-hidden="true">↗</span>
            <span className="visually-hidden"> (새 창)</span>
          </a>
        )}
      </div>
    </>
  )
}
