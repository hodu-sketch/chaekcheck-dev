import { DropdownMenu } from 'radix-ui'
import { useEffect, useId, useRef, useState } from 'react'
import { Link, useLocation } from 'react-router'
import { useAiStatus } from '../features/aisearch/useAiStatus'
import { ChevronDown, Cross, MenuLines } from './icons'
import { MENUS, SEARCH_ITEMS, inMenu } from './headerMenus'
import styles from './HeaderMenu.module.css'

/** 도서검색's ways: both while the AI search is on (or not known yet), else the direct search only. */
function useSearchItems() {
  return useAiStatus() === 'off' ? SEARCH_ITEMS.filter((item) => item.key === 'direct') : SEARCH_ITEMS
}

/**
 * Wide screens (641px and up): the menus right of the logo, plain links (user 2026-10-19) but 도서검색, a button that
 * opens AI 검색 and 직접 검색 under it (user 2026-10-24). The menu of the screen is underlined.
 */
export function HeaderMenu() {
  const { pathname } = useLocation()
  const searchItems = useSearchItems()
  return (
    <nav className={styles.wide} aria-label="주 메뉴">
      <ul className={styles.bigList}>
        {MENUS.map((menu) => {
          const current = inMenu(pathname, menu)
          if (menu.key === 'search' && searchItems.length > 1) {
            return (
              <li key={menu.key} className={styles.big}>
                <DropdownMenu.Root>
                  <DropdownMenu.Trigger
                    className={styles.bigLink}
                    data-big={menu.key}
                    data-current={current ? '' : undefined}
                  >
                    {menu.label}
                    <ChevronDown className={styles.chevron} />
                  </DropdownMenu.Trigger>
                  <DropdownMenu.Portal>
                    <DropdownMenu.Content
                      className={styles.subMenu}
                      side="bottom"
                      align="start"
                      sideOffset={4}
                      // the chosen screen opens: no focus ring left on 도서검색
                      onCloseAutoFocus={(event) => event.preventDefault()}
                    >
                      {searchItems.map((item) => (
                        <DropdownMenu.Item key={item.key} asChild>
                          <Link
                            to={item.to}
                            className={styles.subItem}
                            aria-current={pathname === item.to ? 'page' : undefined}
                          >
                            {item.label}
                          </Link>
                        </DropdownMenu.Item>
                      ))}
                    </DropdownMenu.Content>
                  </DropdownMenu.Portal>
                </DropdownMenu.Root>
              </li>
            )
          }
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
  const searchItems = useSearchItems()
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
                if (menu.key === 'search' && searchItems.length > 1) {
                  // 도서검색 as a heading over its two ways (user 2026-10-24)
                  return (
                    <li key={menu.key}>
                      <p className={styles.drawerGroup}>{menu.label}</p>
                      <ul className={styles.drawerSub}>
                        {searchItems.map((item) => (
                          <li key={item.key}>
                            <Link
                              to={item.to}
                              className={styles.drawerItem}
                              data-sub=""
                              data-current={pathname === item.to ? '' : undefined}
                              aria-current={pathname === item.to ? 'page' : undefined}
                              onClick={() => setOpen(false)}
                            >
                              {item.label}
                            </Link>
                          </li>
                        ))}
                      </ul>
                    </li>
                  )
                }
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
