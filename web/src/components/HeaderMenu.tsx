import { useEffect, useId, useRef, useState } from 'react'
import { Link, useLocation } from 'react-router'
import { Cross, MenuLines } from './icons'
import { MENUS, inMenu } from './headerMenus'
import styles from './HeaderMenu.module.css'

/**
 * Wide screens (641px and up): the three menus right of the logo, each a plain link with no sub-menu (user
 * 2026-10-19). The menu of the screen is underlined.
 */
export function HeaderMenu() {
  const { pathname } = useLocation()
  return (
    <nav className={styles.wide} aria-label="주 메뉴">
      <ul className={styles.bigList}>
        {MENUS.map((menu) => {
          const current = inMenu(pathname, menu)
          return (
            <li key={menu.key} className={styles.big}>
              <Link
                to={menu.to}
                className={styles.bigLink}
                data-big={menu.key}
                data-current={current ? '' : undefined}
                aria-current={current ? 'page' : undefined}
              >
                {menu.label}
              </Link>
            </li>
          )
        })}
      </ul>
    </nav>
  )
}

/**
 * Phones (640px and below): a ☰ button at the far left, before the logo (user 2026-10-19: "로고 좌측이어도 좋아"; the
 * header row itself with a ⌄/⌃ before, which users did not understand). It opens the same three menus under the
 * header with the screen below darkened and turns into ✕; the button again, the darkened part, Esc or a menu closes
 * it.
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

  useEffect(() => {
    if (!open) return
    const onKey = (event: KeyboardEvent) => {
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
      >
        {open ? <Cross size={16} /> : <MenuLines size={24} />}
      </button>
      {open && (
        <>
          <div
            className={styles.dim}
            data-testid="menu-dim"
            style={{ top: opened?.top ?? 0 }}
            onClick={() => setOpen(false)}
          />
          <nav id={drawerId} className={styles.drawer} aria-label="주 메뉴">
            <ul className={styles.drawerList}>
              {MENUS.map((menu) => {
                const current = inMenu(pathname, menu)
                return (
                  <li key={menu.key}>
                    <Link
                      to={menu.to}
                      className={styles.drawerItem}
                      data-current={current ? '' : undefined}
                      aria-current={current ? 'page' : undefined}
                      onClick={() => setOpen(false)}
                    >
                      {menu.label}
                    </Link>
                  </li>
                )
              })}
            </ul>
          </nav>
        </>
      )}
    </div>
  )
}
