/**
 * The header menus (cycle 21 spec R1, ADR-0127): three plain links, no sub-menus (user 2026-10-19). The picks lists
 * (이동진/사서/서울대) are chosen inside the picks page; 내 도서관 and 계정 are one 마이페이지 at /mypage.
 */
export const MENUS = [
  // 소개 is the first main menu, not a small grey link by the logo (user 2026-10-24: "그냥 메인탭으로 첫번째에 박으라니까")
  { key: 'about', label: '소개', to: '/about' },
  { key: 'search', label: '도서검색', to: '/search' },
  { key: 'picks', label: '추천도서', to: '/picks' },
  { key: 'my', label: '마이페이지', to: '/mypage' },
] as const

export type Menu = (typeof MENUS)[number]

/** Whether the screen at {@code pathname} belongs to the menu (underlined). */
export function inMenu(pathname: string, menu: Menu) {
  switch (menu.key) {
    case 'about':
      return pathname === '/about'
    // not the main page: nothing is marked there (user 2026-10-20: "메인에서는 상단에 도서검색 보라색글자랑 보라색 줄 없이")
    case 'search':
      return pathname === '/search' || pathname.startsWith('/books/')
    case 'picks':
      return pathname === '/picks' || pathname.startsWith('/picks/')
    case 'my':
      return pathname === '/mypage' || pathname === '/account'
  }
}
