import { useEffect, useId, useRef, useState } from 'react'
import type { KeyboardEvent, MouseEvent } from 'react'
import { Link, useLocation } from 'react-router'
import styles from './HeaderMenu.module.css'

/** The header menus (cycle 21 spec R1, ADR-0127): two big menus, each with its items. */
export const MENUS = [
  {
    key: 'picks',
    label: '추천도서',
    items: [
      { to: '/picks/leedongjin', label: '이동진 추천도서' },
      { to: '/picks/librarian', label: '사서 추천도서' },
      { to: '/picks/snu100', label: '서울대 100선' },
    ],
  },
  { key: 'my', label: '마이페이지', items: [{ to: '/my-libraries', label: '내 도서관 관리' }] },
] as const

/** Open after the pointer rests this long, close this long after it leaves (spec R1-3, NN/g) [확정]. */
export const MENU_DELAY_MS = 500

/** A finger, not a mouse: first tap opens (spec R1-5). Samsung Internet reports hover, so pointer only (cycle 10). */
const TOUCH_QUERY = '(pointer: coarse)'

function isTouch() {
  return typeof window.matchMedia === 'function' && window.matchMedia(TOUCH_QUERY).matches
}

function inMenu(pathname: string, menu: (typeof MENUS)[number]) {
  return menu.items.some((item) => pathname === item.to || pathname.startsWith(item.to + '/'))
    || (menu.key === 'picks' && pathname === '/picks')
}

/**
 * Wide screens (641px and up): the big menus right of the logo. Resting on one for 0.5s opens both columns at once,
 * each right under its big menu over a full-width band with a line and a shadow; nothing below is darkened (R1-2).
 * Clicking a big menu goes to its first item (R1-3); on touch screens the first tap opens instead. Keyboard: Tab to a
 * big menu, Enter goes, ↓ opens and moves into its column, Esc closes and returns to the big menu.
 */
export function HeaderMenu() {
  const { pathname } = useLocation()
  const [open, setOpen] = useState(false)
  const timer = useRef<number | undefined>(undefined)
  const root = useRef<HTMLDivElement>(null)
  const panelId = useId()

  const clearTimer = () => {
    window.clearTimeout(timer.current)
    timer.current = undefined
  }
  const later = (next: boolean) => {
    clearTimer()
    timer.current = window.setTimeout(() => setOpen(next), MENU_DELAY_MS)
  }

  useEffect(() => clearTimer, [])
  // a new screen closes the menu
  useEffect(() => {
    clearTimer()
    setOpen(false)
  }, [pathname])

  const focusFirstItem = (key: string) => {
    setOpen(true)
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
      setOpen(false)
    }
  }

  const onBigClick = (event: MouseEvent<HTMLAnchorElement>) => {
    if (!open && isTouch()) {
      event.preventDefault()
      clearTimer()
      setOpen(true)
    }
  }

  const onColumnKeyDown = (event: KeyboardEvent<HTMLElement>, key: string) => {
    const links = [...(event.currentTarget.querySelectorAll<HTMLAnchorElement>('a') ?? [])]
    const at = links.indexOf(document.activeElement as HTMLAnchorElement)
    if (event.key === 'Escape') {
      event.preventDefault()
      setOpen(false)
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
      data-open={open ? '' : undefined}
      onMouseEnter={() => later(true)}
      onMouseLeave={() => later(false)}
      onFocus={() => clearTimer()}
      onBlur={(event) => {
        if (!root.current?.contains(event.relatedTarget as Node | null)) setOpen(false)
      }}
    >
      <nav aria-label="주 메뉴">
        <ul className={styles.bigList}>
          {MENUS.map((menu) => (
            <li key={menu.key} className={styles.big}>
              <Link
                to={menu.items[0].to}
                className={styles.bigLink}
                data-big={menu.key}
                data-current={inMenu(pathname, menu) ? '' : undefined}
                aria-expanded={open}
                aria-controls={panelId}
                onClick={onBigClick}
                onKeyDown={(event) => onBigKeyDown(event, menu.key)}
              >
                {menu.label}
              </Link>
              <ul
                className={styles.column}
                data-column={menu.key}
                hidden={!open}
                onKeyDown={(event) => onColumnKeyDown(event, menu.key)}
              >
                {menu.items.map((item) => (
                  <li key={item.to}>
                    <Link
                      to={item.to}
                      className={styles.item}
                      aria-current={pathname === item.to ? 'page' : undefined}
                      onClick={() => setOpen(false)}
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
      {/* the full-width band behind the columns (data4library.kr, 2026-10-16) */}
      <div id={panelId} className={styles.band} hidden={!open} aria-hidden="true" />
    </div>
  )
}

/**
 * Phones (640px and below, spec R1-5): ☰ at the right end. It opens the same menus from the top with the screen below
 * darkened; ✕, the darkened part, Esc or a menu item closes it.
 */
export function PhoneMenu() {
  const { pathname } = useLocation()
  const [open, setOpen] = useState(false)
  const button = useRef<HTMLButtonElement>(null)
  const drawerId = useId()

  useEffect(() => setOpen(false), [pathname])

  useEffect(() => {
    if (!open) return
    const onKey = (event: globalThis.KeyboardEvent) => {
      if (event.key === 'Escape') {
        setOpen(false)
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
        onClick={() => setOpen((was) => !was)}
      >
        <span aria-hidden="true">{open ? '✕' : '☰'}</span>
      </button>
      {open && (
        <>
          <div
            className={styles.dim}
            data-testid="menu-dim"
            style={{ top: button.current?.closest('[data-app-header]')?.getBoundingClientRect().bottom ?? 0 }}
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
