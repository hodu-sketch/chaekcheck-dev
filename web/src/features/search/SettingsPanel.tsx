import { Accordion, Dialog, Popover, RadioGroup } from 'radix-ui'
import { useState, type ReactNode } from 'react'
import { useOptionalAuth } from '../auth/AuthContext'
import { WithdrawDialog } from '../auth/WithdrawDialog'
import { DIRECT_REASON, type SearchSettings, type ViewMode } from './searchSettings'
import styles from './SettingsPanel.module.css'

type Props = {
  settings: SearchSettings
  /** Applied at once; with results the search runs again [안]. */
  onChange: (settings: SearchSettings) => void
  /** Phones: "찾을 도서관" (scope, 방문 없이 가입, 내 도서관/내 지역) at the top of the panel (D안, user choice 2026-10-05). */
  scopeSection?: ReactNode
  /** Phones: what "찾을 도서관" is set to now, shown on its row while the row is closed (e.g. "내 도서관"). */
  scopeValue?: string
  /** A dot on the button: the search scope is not the default, while it is folded in here. */
  marked?: boolean
}

type SectionKey = 'scope' | 'view' | 'direct'

/**
 * Phones: the settings open as a bottom sheet (cycle 08, flow.md 검토 #1). Set to false to go back to the small
 * window under the button; the popover code stays for that (user 2026-10-06: "일단 만들고 나서 내가 이전으로돌아가라고
 * 하면 돌아가줘"). Wider screens always use the small window.
 */
export const PHONE_SETTINGS_SHEET = true

/** How far down the handle must be pulled to close the sheet [안]. */
const CLOSE_DRAG_PX = 80

/**
 * S4 settings (flow.md): 보기 방식 and 도서관에서 직접 찾기, in a small window under ⚙ (a bottom sheet on phones). Kept in
 * this browser and in the address [확정]. With direct search on, 책 먼저 고르기 cannot be used [안]: site results are
 * not grouped by book. Signed in, a "계정" section holds only 탈퇴 (cycle 05 [확정]); login and logout stay in the
 * header.
 */
export function SettingsPanel({ settings, onChange, scopeSection, scopeValue, marked = false }: Props) {
  const auth = useOptionalAuth()
  const [open, setOpen] = useState(false)
  const [withdrawOpen, setWithdrawOpen] = useState(false)
  const signedIn = auth?.state.status === 'signed-in' ? auth.state : null
  const provider = signedIn?.me.loginMethods[0]
  // phones (the scope folded in here): one row per section that opens on a tap, all closed at first (user requests
  // 2026-10-05)
  const sheet = scopeSection !== undefined
  const [section, setSection] = useState<SectionKey | ''>('')

  const viewOptions = (
    <RadioGroup.Root
      className={styles.options}
      value={settings.view}
      onValueChange={(value) => onChange({ ...settings, view: value as ViewMode })}
      aria-label="보기 방식"
    >
      <Option value="instant" label="바로 보기" hint="찾은 책의 대출·예약을 바로 보여 줘요" />
      <Option
        value="books"
        label="책 먼저 고르기"
        hint={settings.direct ? DIRECT_REASON : '책을 고른 뒤 도서관별로 보여 줘요'}
        disabled={settings.direct}
      />
    </RadioGroup.Root>
  )
  const directOptions = (
    <RadioGroup.Root
      className={styles.options}
      value={settings.direct ? 'on' : 'off'}
      onValueChange={(value) => onChange({ ...settings, direct: value === 'on' })}
      aria-label="도서관에서 직접 찾기"
    >
      <Option value="off" label="끔" hint="수집한 책 목록에서 찾아요" />
      <Option value="on" label="켬" hint="도서관 사이트에서 제목 하나로 찾아요" />
    </RadioGroup.Root>
  )

  const trigger = (
    <>
      {marked && <span className={styles.mark} aria-hidden="true" />}
      {/* two sliders with rounded-square knobs, to match the rounded-rectangle look (user request 2026-10-05) */}
      <svg width="22" height="22" viewBox="0 0 20 20" aria-hidden="true" focusable="false">
        <path d="M3 6.5h14M3 13.5h14" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" />
        <rect x="11" y="3.75" width="4.5" height="5.5" rx="1.3" fill="currentColor" />
        <rect x="4.5" y="10.75" width="4.5" height="5.5" rx="1.3" fill="currentColor" />
      </svg>
    </>
  )
  const triggerLabel = marked ? '검색 설정 (찾을 도서관 바꿈)' : '검색 설정'

  const body = (
    <>
      {sheet ? (
        <Accordion.Root
          type="single"
          collapsible
          value={section}
          onValueChange={(value) => setSection(value as SectionKey | '')}
          className={styles.sections}
        >
          <Section value="scope" title="찾을 도서관" current={scopeValue}>
            <div className={styles.scope}>{scopeSection}</div>
          </Section>
          <Section value="view" title="보기 방식" current={settings.view === 'books' ? '책 먼저 고르기' : '바로 보기'}>
            {viewOptions}
          </Section>
          <Section value="direct" title="도서관에서 직접 찾기" current={settings.direct ? '켬' : '끔'}>
            {directOptions}
          </Section>
        </Accordion.Root>
      ) : (
        <>
          <fieldset className={styles.group}>
            <legend className={styles.legend}>보기 방식</legend>
            {viewOptions}
          </fieldset>
          <fieldset className={styles.group}>
            <legend className={styles.legend}>도서관에서 직접 찾기</legend>
            {directOptions}
          </fieldset>
        </>
      )}
      {provider && (
        // "계정" and 탈퇴 on one line, the button at the right like the other controls (user request 2026-10-05)
        <div className={`${styles.group} ${styles.accountRow}`} role="group" aria-labelledby="settings-account">
          <span id="settings-account" className={styles.legend}>
            계정
          </span>
          <button
            type="button"
            className={styles.withdraw}
            onClick={() => {
              setOpen(false)
              setWithdrawOpen(true)
            }}
          >
            탈퇴
          </button>
        </div>
      )}
    </>
  )

  return (
    <>
      {sheet && PHONE_SETTINGS_SHEET ? (
        <BottomSheet open={open} onOpenChange={setOpen} title="검색 설정" trigger={trigger} triggerLabel={triggerLabel}>
          {body}
        </BottomSheet>
      ) : (
        <Popover.Root open={open} onOpenChange={setOpen}>
          <Popover.Trigger className={styles.trigger} aria-label={triggerLabel}>
            {trigger}
          </Popover.Trigger>
          <Popover.Portal>
            <Popover.Content
              className={styles.content}
              data-sheet={sheet || undefined}
              side="bottom"
              align="end"
              sideOffset={8}
              collisionPadding={12}
              // wider screens: never flipped above the button (it covered the headline and went past the top on short
              // laptops, user request 2026-10-08); its height is capped instead. The phone window keeps the collision
              // handling, which also keeps it inside the screen's width.
              avoidCollisions={sheet}
            >
              {body}
              <Popover.Arrow className={styles.arrow} width={12} height={6} />
            </Popover.Content>
          </Popover.Portal>
        </Popover.Root>
      )}
      {provider && (
        <WithdrawDialog
          open={withdrawOpen}
          onOpenChange={(next) => {
            setWithdrawOpen(next)
            // 그만두기 goes back to the settings (S4)
            if (!next) setOpen(true)
          }}
          provider={provider}
        />
      )}
    </>
  )
}

/**
 * Phones (cycle 08, flow.md 검토 #1): the settings in a sheet from the bottom of the screen, with a handle; a tap
 * outside or pulling the handle down closes it. Same rows in the same order as the small window it replaces.
 */
function BottomSheet({
  open,
  onOpenChange,
  title,
  trigger,
  triggerLabel,
  children,
}: {
  open: boolean
  onOpenChange: (open: boolean) => void
  title: string
  trigger: ReactNode
  triggerLabel: string
  children: ReactNode
}) {
  const [drag, setDrag] = useState<{ startY: number; offset: number } | null>(null)

  const endDrag = () => {
    if (drag && drag.offset > CLOSE_DRAG_PX) onOpenChange(false)
    setDrag(null)
  }

  return (
    <Dialog.Root open={open} onOpenChange={onOpenChange}>
      <Dialog.Trigger className={styles.trigger} aria-label={triggerLabel}>
        {trigger}
      </Dialog.Trigger>
      <Dialog.Portal>
        <Dialog.Overlay className={styles.sheetOverlay} />
        <Dialog.Content
          className={styles.bottomSheet}
          aria-describedby={undefined}
          style={drag ? { transform: `translateY(${drag.offset}px)`, transition: 'none' } : undefined}
        >
          <Dialog.Title className="visually-hidden">{title}</Dialog.Title>
          <div
            className={styles.handleArea}
            data-testid="sheet-handle"
            onPointerDown={(event) => {
              event.currentTarget.setPointerCapture?.(event.pointerId)
              setDrag({ startY: event.clientY, offset: 0 })
            }}
            onPointerMove={(event) => {
              if (drag) setDrag({ ...drag, offset: Math.max(0, event.clientY - drag.startY) })
            }}
            onPointerUp={endDrag}
            onPointerCancel={() => setDrag(null)}
          >
            <span className={styles.handle} aria-hidden="true" />
          </div>
          <div className={styles.sheetBody}>{children}</div>
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  )
}

/** One row of the phone settings: the title and what it is set to now; a tap opens the controls under it. */
function Section({
  value,
  title,
  current,
  children,
}: {
  value: SectionKey
  title: string
  current?: string
  children: ReactNode
}) {
  return (
    <Accordion.Item value={value} className={styles.section}>
      <Accordion.Header className={styles.sectionHeader}>
        <Accordion.Trigger className={styles.sectionTrigger}>
          <span className={styles.sectionTitle}>{title}</span>
          {current && <span className={styles.sectionValue}>{current}</span>}
          <svg className={styles.chevron} width="12" height="8" viewBox="0 0 12 8" aria-hidden="true" focusable="false">
            <path d="M1 1.5l5 5 5-5" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" />
          </svg>
        </Accordion.Trigger>
      </Accordion.Header>
      <Accordion.Content className={styles.sectionBody}>{children}</Accordion.Content>
    </Accordion.Item>
  )
}

function Option({ value, label, hint, disabled }: { value: string; label: string; hint: string; disabled?: boolean }) {
  return (
    <label className={styles.option} data-disabled={disabled || undefined}>
      <RadioGroup.Item value={value} className={styles.radio} disabled={disabled}>
        <RadioGroup.Indicator className={styles.dot} />
      </RadioGroup.Item>
      <span>
        <span className={styles.optionLabel}>{label}</span>
        <span className={styles.hint}>{hint}</span>
      </span>
    </label>
  )
}
