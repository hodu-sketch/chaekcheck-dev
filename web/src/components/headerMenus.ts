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

export type Menu = (typeof MENUS)[number]

/** Open after the pointer rests this long, close this long after it leaves (spec R1-3, NN/g) [확정]. */
export const MENU_DELAY_MS = 500

/** Whether the screen at {@code pathname} is one of the menu's items (underlined big menu). */
export function inMenu(pathname: string, menu: Menu) {
  return (
    menu.items.some((item) => pathname === item.to || pathname.startsWith(item.to + '/')) ||
    (menu.key === 'picks' && pathname === '/picks')
  )
}
