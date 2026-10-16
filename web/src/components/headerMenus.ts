/**
 * The header menus (cycle 21 spec R1, ADR-0127; user 2026-10-16: "폰이랑 pc랑 둘 다 탭에 도서검색 넣어줘"): three big
 * menus, each with its items.
 */
export const MENUS = [
  { key: 'search', label: '도서검색', items: [{ to: '/search', label: '전자책 검색' }] },
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

export type Menu = (typeof MENUS)[number]

/**
 * The logo goes to the main page and opens the menus there, or closes them when they are open on it, on every width
 * (user 2026-10-16: "좌상단 책첵 로고 눌렀을 때 이미 아래 탭들이 늘려 있다면 닫아줘 … 안 늘려 있다면 늘려주고", "폰 말고 pc도
 * 똑같이", then "토글 기능이 있되 기본적으로 메인 페이지로 가는걸로", choice "메인 + 메뉴 열기/닫기"). The logo sends this
 * event with the address it goes to (`detail`); HeaderMenu and PhoneMenu open or close their menus on that address.
 */
export const MENU_TOGGLE_EVENT = 'chaekcheck:menu-toggle'

/** Sends MENU_TOGGLE_EVENT for the screen at `path`. */
export function toggleMenusAt(path: string) {
  window.dispatchEvent(new CustomEvent<string>(MENU_TOGGLE_EVENT, { detail: path }))
}

/** Open after the pointer rests this long, close this long after it leaves (spec R1-3, NN/g) [확정]. */
export const MENU_DELAY_MS = 500

/** Whether the screen at {@code pathname} is one of the menu's items (underlined big menu). */
export function inMenu(pathname: string, menu: Menu) {
  if (menu.key === 'search') return pathname === '/' || pathname === '/search' || pathname.startsWith('/books/')
  return (
    menu.items.some((item) => pathname === item.to || pathname.startsWith(item.to + '/')) ||
    (menu.key === 'picks' && pathname === '/picks')
  )
}
