/**
 * The header menus (cycle 21 spec R1, ADR-0127; user 2026-10-16: "폰이랑 pc랑 둘 다 탭에 도서검색 넣어줘"): the big
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
  {
    key: 'my',
    label: '마이페이지',
    // 계정 (user request 2026-10-18): 탈퇴 moved here from the search settings
    items: [
      { to: '/my-libraries', label: '내 도서관 관리' },
      { to: '/account', label: '계정' },
    ],
  },
] as const

export type Menu = (typeof MENUS)[number]

/**
 * 책첵이란? (the static /about page): a quiet grey link right next to the logo on every width, out of the menus and
 * the phone menu (user 2026-10-19: "좀 더 회색으로 해서 좌상단 책첵 로고 바로 오른쪽에 오도록").
 */
export const ABOUT_LINK = { label: '책첵이란?', to: '/about' } as const

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
