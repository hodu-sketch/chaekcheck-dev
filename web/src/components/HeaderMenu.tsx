import { useEffect, useId, useRef, useState } from 'react'
import type { CSSProperties, KeyboardEvent, MouseEvent } from 'react'
import { Link, useLocation } from 'react-router'
import { useHeaderOpen } from './HeaderOpen'
import { MENU_DELAY_MS, MENUS, inMenu } from './headerMenus'
import styles from './HeaderMenu.module.css'

/**
 * Cycle 20 (handoff 1-12 [확정]): the menus and the lookup status popover never show together. Open menus claim the
 * header; when the popover claims it instead, `close` runs.
 */
function useExclusiveWithStatus(open: boolean, close: () => void) {
  const { owner, claim, release } = useHeaderOpen()
  useEffect(() => {
    if (open) claim('menu')
    else release('menu')
  }, [open, claim, release])
  useEffect(() => {
    if (open && owner === 'status') close()
    // close only sets state
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [owner])
}

/**
 * Wide screens (641px and up): the big menus right of the logo. Every column opens at once, each right under its big
 * menu, over a full-width band; nothing below is darkened (R1-2; user 2026-10-16: "나는 다 보였으면 하는데"). A big
 * menu's cell is as wide as its word or its longest item, whichever is wider, and the cells are 36px apart, so the
 * open columns never run into each other ("상단 탭 글자간의 거리 줄이고", "글자가 겹치면 좀 거리를 띄우던가").
 * Resting on a big menu for 0.5s opens them. Clicking a big menu opens them without leaving the screen, and clicking
 * it again closes them ("pc 탭 말이야 누르면 아래 항목 보이도록 해줘"; it went to its first item before, R1-3).
 * Keyboard: Tab to a big menu, Enter opens or closes, ↓ opens and moves into its column, Esc closes and returns to the
 * big menu.
 */
/** The label with the most characters (Korean labels are near one width per character). */
function longest(labels: string[]) {
  return labels.reduce((a, b) => (b.length > a.length ? b : a), '')
}

export function HeaderMenu() {
  const { pathname } = useLocation()
  // open on the screen it was opened on: a new screen closes it
  const [opened, setOpened] = useState<{ at: string; key: string } | null>(null)
  const openKey = opened?.at === pathname ? opened.key : null
  // opened by a click: a second click closes it; opened by resting the pointer: a click keeps it open
  const clicked = useRef(false)
  const setOpen = (key: string | null) => {
    if (key === null) clicked.current = false
    setOpened(key === null ? null : { at: pathname, key })
  }
  const timer = useRef<number | undefined>(undefined)
  const root = useRef<HTMLDivElement>(null)
  const panelId = useId()

  const clearTimer = () => {
    window.clearTimeout(timer.current)
    timer.current = undefined
  }
  const later = (key: string | null) => {
    clearTimer()
    timer.current = window.setTimeout(() => setOpen(key), MENU_DELAY_MS)
  }
  const onBigEnter = (key: string) => {
    if (openKey === null) later(key)
    else clearTimer()
  }

  useEffect(() => clearTimer, [])
  useExclusiveWithStatus(openKey !== null, () => setOpen(null))

  // open: a press anywhere else closes them (opened by resting the pointer, focus may never have come in, so blur
  // alone is not enough)
  useEffect(() => {
    if (!openKey) return
    const onDown = (event: PointerEvent) => {
      if (!root.current?.contains(event.target as Node | null)) setOpen(null)
    }
    document.addEventListener('pointerdown', onDown)
    return () => document.removeEventListener('pointerdown', onDown)
    // setOpen only sets state
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [openKey])

  const focusFirstItem = (key: string) => {
    setOpen(key)
    // the column is in the DOM already (hidden); focus it once it shows
    window.requestAnimationFrame(() => {
      root.current?.querySelector<HTMLAnchorElement>(`[data-column="${key}"] a`)?.focus()
    })
  }

  const onBigKeyDown = (event: KeyboardEvent<HTMLAnchorElement>, key: string) => {
    if (event.key === 'ArrowDown') {
      event.preventDefault()
      clearTimer()
      focusFirstItem(key)
    } else if (event.key === 'Escape') {
      clearTimer()
      setOpen(null)
    }
  }

  const onBigClick = (event: MouseEvent<HTMLAnchorElement>, key: string) => {
    event.preventDefault()
    clearTimer()
    // the same big menu again closes them; another one keeps them open (every column is open anyway)
    if (openKey === key && clicked.current) {
      setOpen(null)
    } else {
      setOpen(key)
      clicked.current = true
    }
  }

  const onColumnKeyDown = (event: KeyboardEvent<HTMLElement>, key: string) => {
    const links = [...(event.currentTarget.querySelectorAll<HTMLAnchorElement>('a') ?? [])]
    const at = links.indexOf(document.activeElement as HTMLAnchorElement)
    if (event.key === 'Escape') {
      event.preventDefault()
      setOpen(null)
      root.current?.querySelector<HTMLAnchorElement>(`[data-big="${key}"]`)?.focus()
    } else if (event.key === 'ArrowDown' && at < links.length - 1) {
      event.preventDefault()
      links[at + 1].focus()
    } else if (event.key === 'ArrowUp') {
      event.preventDefault()
      if (at > 0) links[at - 1].focus()
      else root.current?.querySelector<HTMLAnchorElement>(`[data-big="${key}"]`)?.focus()
    }
  }

  return (
    <div
      ref={root}
      className={styles.wide}
      data-open={openKey ? '' : undefined}
      onMouseLeave={() => later(null)}
      onFocus={() => clearTimer()}
      onBlur={(event) => {
        if (!root.current?.contains(event.relatedTarget as Node | null)) setOpen(null)
      }}
    >
      <nav aria-label="주 메뉴">
        <ul className={styles.bigList}>
          {MENUS.map((menu) => (
            <li key={menu.key} className={styles.big} onMouseEnter={() => onBigEnter(menu.key)}>
              <Link
                to={menu.items[0].to}
                className={styles.bigLink}
                data-big={menu.key}
                data-current={inMenu(pathname, menu) ? '' : undefined}
                aria-expanded={openKey !== null}
                aria-controls={`${panelId}-${menu.key}`}
                onClick={(event) => onBigClick(event, menu.key)}
                onKeyDown={(event) => onBigKeyDown(event, menu.key)}
              >
                {menu.label}
              </Link>
              {/* no height: makes the cell as wide as the longest item, so the open columns keep apart */}
              <span className={styles.sizer} aria-hidden="true">
                {longest(menu.items.map((item) => item.label))}
              </span>
              <ul
                id={`${panelId}-${menu.key}`}
                className={styles.column}
                data-column={menu.key}
                hidden={openKey === null}
                onKeyDown={(event) => onColumnKeyDown(event, menu.key)}
              >
                {menu.items.map((item) => (
                  <li key={item.to}>
                    <Link
                      to={item.to}
                      className={styles.item}
                      aria-current={pathname === item.to ? 'page' : undefined}
                      onClick={() => setOpen(null)}
                    >
                      {item.label}
                    </Link>
                  </li>
                ))}
              </ul>
            </li>
          ))}
        </ul>
      </nav>
      {/* the full-width band behind the columns, as tall as the longest (data4library.kr, 2026-10-16) */}
      <div
        className={styles.band}
        hidden={!openKey}
        aria-hidden="true"
        style={{ '--rows': Math.max(...MENUS.map((m) => m.items.length)) } as CSSProperties}
      />
    </div>
  )
}

/**
 * Phones (640px and below; user 2026-10-16: "오른쪽 위에 누르면 메뉴 보이는게 아니라 그냥 상단 탭 자체를 누르면 메뉴
 * 보이는 걸로"): no ☰; the header row itself is the button (the logo and 로그인 keep their own taps). It opens the same
 * menus under the header with the screen below darkened; the row again, the darkened part, Esc or a menu item closes it.
 */
export function PhoneMenu() {
  const { pathname } = useLocation()
  // where it was opened and the header's bottom then (the darkening starts there); a new screen closes it
  const [opened, setOpened] = useState<{ at: string; top: number } | null>(null)
  const open = opened?.at === pathname
  const button = useRef<HTMLButtonElement>(null)
  const drawerId = useId()

  const setOpen = (next: boolean) => {
    const header = button.current?.closest('[data-app-header]')
    setOpened(next ? { at: pathname, top: header?.getBoundingClientRect().bottom ?? 0 } : null)
  }
  useExclusiveWithStatus(open, () => setOpened(null))

  useEffect(() => {
    if (!open) return
    const onKey = (event: globalThis.KeyboardEvent) => {
      if (event.key === 'Escape') {
        setOpened(null)
        button.current?.focus()
      }
    }
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  }, [open])

  return (
    <div className={styles.phone}>
      <button
        ref={button}
        type="button"
        className={styles.menuButton}
        aria-expanded={open}
        aria-controls={drawerId}
        aria-label={open ? '메뉴 닫기' : '메뉴 열기'}
        onClick={() => setOpen(!open)}
      />
      {open && (
        <>
          <div
            className={styles.dim}
            data-testid="menu-dim"
            style={{ top: opened?.top ?? 0 }}
            onClick={() => setOpen(false)}
          />
          <nav id={drawerId} className={styles.drawer} aria-label="주 메뉴">
            {MENUS.map((menu) => (
              <section key={menu.key} className={styles.drawerGroup}>
                <h2 className={styles.drawerTitle} data-current={inMenu(pathname, menu) ? '' : undefined}>
                  {menu.label}
                </h2>
                <ul className={styles.drawerList}>
                  {menu.items.map((item) => (
                    <li key={item.to}>
                      <Link
                        to={item.to}
                        className={styles.drawerItem}
                        aria-current={pathname === item.to ? 'page' : undefined}
                        onClick={() => setOpen(false)}
                      >
                        {item.label}
                      </Link>
                    </li>
                  ))}
                </ul>
              </section>
            ))}
          </nav>
        </>
      )}
    </div>
  )
}
